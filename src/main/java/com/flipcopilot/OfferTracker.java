package com.flipcopilot;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * Keeps the per-slot clocks and the cost basis of everything we hold, and appends every fill to
 * {@code ~/.runelite/flip-copilot/ledger.csv} (same columns as {@code flipping/ledger.csv}).
 *
 * <p>{@link #update} is called on the client thread from {@code GrandExchangeOfferChanged}; persistence is
 * handed back to the caller via {@link #dirty()} so disk IO can happen on the executor.
 */
@Slf4j
public class OfferTracker
{
	private static final DateTimeFormatter LEDGER_TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
	private static final Type OFFERS_TYPE = new TypeToken<List<TrackedOffer>>() {}.getType();
	private static final Type POSITIONS_TYPE = new TypeToken<List<Position>>() {}.getType();

	private final Map<Integer, TrackedOffer> slots = new TreeMap<>();
	private final Map<Integer, Position> positions = new HashMap<>();
	private final List<String> pendingLedger = new ArrayList<>();
	private final Gson gson;
	private final File dir;
	private volatile boolean dirty;

	public OfferTracker(Gson gson, File dir)
	{
		this.gson = gson;
		this.dir = dir;
	}

	public synchronized List<TrackedOffer> offers()
	{
		return new ArrayList<>(slots.values());
	}

	public synchronized TrackedOffer offer(int slot)
	{
		return slots.get(slot);
	}

	public synchronized Position position(int itemId)
	{
		return positions.get(itemId);
	}

	public synchronized List<Position> positions()
	{
		List<Position> out = new ArrayList<>();
		for (Position p : positions.values())
		{
			if (p.getQty() > 0)
			{
				out.add(p);
			}
		}
		return out;
	}

	public synchronized long realizedTotal()
	{
		long t = 0;
		for (Position p : positions.values())
		{
			t += p.getRealized();
		}
		return t;
	}

	public boolean dirty()
	{
		return dirty;
	}

	/** Apply one offer event. Returns a human line for a completed fill (for notifications), else null. */
	public synchronized String update(int slot, GrandExchangeOffer o, String itemName, long nowMs)
	{
		GrandExchangeOfferState st = o.getState();
		if (st == GrandExchangeOfferState.EMPTY || o.getItemId() <= 0)
		{
			if (slots.remove(slot) != null)
			{
				dirty = true;
			}
			return null;
		}
		boolean buy = st == GrandExchangeOfferState.BUYING || st == GrandExchangeOfferState.BOUGHT || st == GrandExchangeOfferState.CANCELLED_BUY;
		TrackedOffer t = slots.get(slot);
		boolean late = false;
		if (t == null || !t.sameOffer(o.getItemId(), buy, o.getPrice(), o.getTotalQuantity()))
		{
			// New offer in this slot (or a relist): start the clock. If it already has fills we came in late
			// (fresh install, lost offers.json): learn the cost basis but don't treat it as an instant fill.
			t = new TrackedOffer(slot, o.getItemId(), itemName, buy, o.getPrice(), o.getTotalQuantity(), 0, 0, st.name(), nowMs, 0, 0);
			slots.put(slot, t);
			late = o.getQuantitySold() > 0;
		}
		int dq = o.getQuantitySold() - t.getSold();
		int dspent = o.getSpent() - t.getSpent();
		String note = null;
		if (dq > 0 && late)
		{
			applyFill(t, dq, dspent, nowMs, false, true);
		}
		else if (dq > 0)
		{
			if (t.getFirstFillAtMs() == 0)
			{
				t.setFirstFillAtMs(nowMs);
			}
			t.setLastFillAtMs(nowMs);
			note = applyFill(t, dq, dspent, nowMs, o.getQuantitySold() >= o.getTotalQuantity(), false);
		}
		t.setSold(o.getQuantitySold());
		t.setSpent(o.getSpent());
		t.setState(st.name());
		dirty = true;
		return note;
	}

	private String applyFill(TrackedOffer t, int dq, int dspent, long nowMs, boolean complete, boolean late)
	{
		Position p = positions.computeIfAbsent(t.getItemId(), id -> new Position(id, t.getName(), 0, 0, 0));
		p.setName(t.getName());
		String ts = LEDGER_TS.format(Instant.ofEpochMilli(nowMs));
		String when = late ? "already filled when first seen" : "after " + (nowMs - t.getPlacedAtMs()) / 1000 + "s";
		long secs = (nowMs - t.getPlacedAtMs()) / 1000;
		if (t.isBuy())
		{
			p.setQty(p.getQty() + dq);
			p.setCost(p.getCost() + dspent);
			pendingLedger.add(String.format("%s,%s,buy,%d,%d,0,%d,,%s %d/%d %s", ts, csv(t.getName()), dq,
				dq > 0 ? dspent / dq : t.getPrice(), -dspent, complete ? "FILLED" : "partial", t.getSold() + dq, t.getTotal(), when));
			return complete ? String.format("Bought %d x %s @ %,d (%ds)", t.getTotal(), t.getName(), t.getPrice(), secs) : null;
		}
		int tax = GeTax.tax(t.getPrice());
		long net = (long) dq * (t.getPrice() - tax);
		long realized = 0;
		if (p.getQty() > 0)
		{
			long matched = Math.min(dq, p.getQty());
			long costOut = Math.round(p.getCost() * (matched / (double) p.getQty()));
			realized = (long) matched * (t.getPrice() - tax) - costOut;
			p.setQty(p.getQty() - matched);
			p.setCost(p.getCost() - costOut);
			p.setRealized(p.getRealized() + realized);
		}
		pendingLedger.add(String.format("%s,%s,sell,%d,%d,%d,%d,,%s %d/%d %s realized %+d", ts, csv(t.getName()), dq,
			t.getPrice(), tax, net, complete ? "SOLD" : "partial", t.getSold() + dq, t.getTotal(), when, realized));
		return complete ? String.format("Sold %d x %s @ %,d (%ds) realized %+,d", t.getTotal(), t.getName(), t.getPrice(), secs, realized) : null;
	}

	private static String csv(String s)
	{
		return s != null && s.contains(",") ? '"' + s + '"' : String.valueOf(s);
	}

	// ---------------- persistence (executor thread) ----------------

	public void load()
	{
		List<TrackedOffer> offers = read(new File(dir, "offers.json"), OFFERS_TYPE);
		List<Position> pos = read(new File(dir, "positions.json"), POSITIONS_TYPE);
		synchronized (this)
		{
			slots.clear();
			if (offers != null)
			{
				for (TrackedOffer t : offers)
				{
					slots.put(t.getSlot(), t);
				}
			}
			positions.clear();
			if (pos != null)
			{
				for (Position p : pos)
				{
					positions.put(p.getItemId(), p);
				}
			}
		}
	}

	public void save()
	{
		List<TrackedOffer> offers;
		List<Position> pos;
		List<String> ledger;
		synchronized (this)
		{
			if (!dirty)
			{
				return;
			}
			dirty = false;
			offers = new ArrayList<>(slots.values());
			pos = new ArrayList<>(positions.values());
			ledger = new ArrayList<>(pendingLedger);
			pendingLedger.clear();
		}
		dir.mkdirs();
		write(new File(dir, "offers.json"), offers, OFFERS_TYPE);
		write(new File(dir, "positions.json"), pos, POSITIONS_TYPE);
		if (!ledger.isEmpty())
		{
			File f = new File(dir, "ledger.csv");
			boolean fresh = !f.isFile();
			try (FileWriter w = new FileWriter(f, true))
			{
				if (fresh)
				{
					w.write("date_utc,item,side,qty,price,tax_per_unit,total_net,est_margin,note\n");
				}
				for (String line : ledger)
				{
					w.write(line);
					w.write('\n');
				}
			}
			catch (IOException ex)
			{
				log.warn("flip-copilot: ledger append failed", ex);
			}
		}
	}

	private <T> T read(File f, Type type)
	{
		if (!f.isFile())
		{
			return null;
		}
		try
		{
			return gson.fromJson(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8), type);
		}
		catch (Exception ex)
		{
			log.warn("flip-copilot: could not read {}", f, ex);
			return null;
		}
	}

	private void write(File f, Object value, Type type)
	{
		try
		{
			Files.write(f.toPath(), gson.toJson(value, type).getBytes(StandardCharsets.UTF_8));
		}
		catch (IOException ex)
		{
			log.warn("flip-copilot: could not write {}", f, ex);
		}
	}

	/** Test hook. */
	synchronized void putPosition(Position p)
	{
		positions.put(p.getItemId(), p);
	}

	synchronized List<String> drainLedger()
	{
		List<String> out = new ArrayList<>(pendingLedger);
		pendingLedger.clear();
		return Collections.unmodifiableList(out);
	}
}
