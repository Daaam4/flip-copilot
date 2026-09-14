package com.flipcopilot;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Value;

/**
 * One item's flip analysis as label/value rows, built once and rendered either as overlay lines
 * (AWT colours) or sidebar HTML. Keeps the offer-setup overlay, the search lookup and the
 * right-click lookup showing identical numbers.
 */
final class ItemReport
{
	enum Tone
	{
		GOOD, WARN, BAD, DIM, TEXT
	}

	@Value
	static class Row
	{
		/** Empty label = full-width note. */
		String label;
		String value;
		Tone tone;

		boolean isNote()
		{
			return label.isEmpty();
		}
	}

	private ItemReport()
	{
	}

	/** Sidebar lookup: both sides of the flip plus what we hold. */
	static List<Row> lookup(FlipCopilotPlugin plugin, ItemMeta meta, Quote q, long nowEpochSec)
	{
		List<Row> rows = new ArrayList<>();
		if (q == null || !q.isComplete())
		{
			rows.add(new Row("Market", "no quote yet", Tone.DIM));
			return rows;
		}
		ScanParams params = plugin.scanParams(plugin.budget());
		FlipCandidate c = FlipScanner.describe(meta, q, plugin.getSnapshot(), params, nowEpochSec);
		FlipCopilotPlugin.CachedStats st = plugin.statsFor(meta.getId());
		Position pos = plugin.tracker().position(meta.getId());
		int sellAt = c.getSell();

		rows.add(new Row("Buy @", fmt(q.getLow()) + " (sellers, " + age(q.getLowTime(), nowEpochSec) + ")", Tone.GOOD));
		rows.add(new Row("Sell @", fmt(sellAt) + " (buyers, " + age(q.getHighTime(), nowEpochSec) + ")", Tone.GOOD));
		rows.add(new Row("Margin / unit", fmt(c.getMargin()) + " (" + String.format("%.1f%%", c.getRoi() * 100) + ")", marginTone(c)));
		rows.add(medianRow(st));
		rows.add(new Row("Break-even", fmt(GeTax.breakEven(q.getLow())) + " (tax " + fmt(GeTax.tax(sellAt)) + ")", Tone.DIM));
		rows.add(new Row("Flow / hr", Ascii.compact(c.getBuySideVolPerHr()) + " sellers, " + Ascii.compact(c.getSellSideVolPerHr()) + " buyers",
			Math.min(c.getBuySideVolPerHr(), c.getSellSideVolPerHr()) < 20 ? Tone.WARN : Tone.DIM));
		if (c.getUnits() > 0)
		{
			rows.add(new Row("Cycle", String.format("x%,d = %s, buy %s + sell %s = %s profit", c.getUnits(), Fmt.gp(c.getCapital()),
				Fmt.min(c.getBuyMin()), Fmt.min(c.getSellMin()), Fmt.gp(c.getProfit())), Tone.TEXT));
			rows.add(new Row("gp / hr", Fmt.gp(c.getGpPerHr()) + " (" + Fmt.pct(c.getRoiPerHr()) + " of capital)", Tone.TEXT));
		}
		else
		{
			rows.add(new Row("Cycle", "0 units fit the budget / cycle cap", Tone.WARN));
		}
		rows.add(limitRow(plugin, meta));
		if (pos != null && pos.getQty() > 0)
		{
			long est = pos.getQty() * (GeTax.net(sellAt) - pos.avgCost());
			rows.add(new Row("Holding", String.format("x%,d @ %,d, be %,d, est %+,d at %s", pos.getQty(), pos.avgCost(), pos.breakEven(), est, fmt(sellAt)),
				est >= 0 ? Tone.GOOD : Tone.BAD));
		}
		guards(plugin, rows, c, st, q, nowEpochSec);
		return rows;
	}

	/** Offer-setup overlay, buy side. */
	static List<Row> buySide(FlipCopilotPlugin plugin, ItemMeta meta, Quote q, int typed, int qty, long nowEpochSec)
	{
		List<Row> rows = new ArrayList<>();
		if (q == null || !q.isComplete())
		{
			rows.add(new Row("Market", "no quote yet", Tone.DIM));
			return rows;
		}
		ScanParams params = plugin.scanParams(plugin.budget());
		FlipCandidate c = FlipScanner.describe(meta, q, plugin.getSnapshot(), params, nowEpochSec);
		FlipCopilotPlugin.CachedStats st = plugin.statsFor(meta.getId());
		int sellAt = c.getSell();

		rows.add(new Row("Place @", fmt(q.getLow()) + " (sellers)", Tone.GOOD));
		rows.add(new Row("Patient", fmt(q.getLow() - 1) + " (1 under, bulk)", Tone.DIM));
		rows.add(new Row("Sellers / buyers", fmt(q.getLow()) + " / " + fmt(q.getHigh()), Tone.TEXT));
		rows.add(new Row("Sell target", fmt(sellAt) + " (be " + fmt(GeTax.breakEven(q.getLow())) + ")", Tone.DIM));
		rows.add(new Row("Margin / unit", fmt(c.getMargin()) + " (" + String.format("%.1f%%", c.getRoi() * 100) + ")", marginTone(c)));
		rows.add(medianRow(st));
		rows.add(new Row("Sellers / hr", Ascii.compact(c.getBuySideVolPerHr()), c.getBuySideVolPerHr() < 20 ? Tone.WARN : Tone.DIM));
		rows.add(limitRow(plugin, meta));
		if (qty > 0)
		{
			double min = c.getBuySideVolPerHr() > 0 ? qty / (c.getBuySideVolPerHr() * params.getVolShare()) * 60 : 999;
			rows.add(new Row("Fill est x" + qty, Fmt.min(min) + ", " + Fmt.gp((double) qty * q.getLow()), min > params.getMaxCycleMin() ? Tone.WARN : Tone.DIM));
		}
		if (typed > 0)
		{
			rows.add(new Row("Your price", fmt(typed), typed <= q.getLow() ? Tone.GOOD : typed <= q.mid() ? Tone.WARN : Tone.BAD));
			if (typed > q.mid())
			{
				rows.add(new Row("", "past the midpoint: overpaying by " + fmt(typed - q.getLow()) + "/unit", Tone.BAD));
			}
			else if (typed >= q.getHigh())
			{
				rows.add(new Row("", "at the buyer price: zero margin", Tone.BAD));
			}
			else if (typed > q.getLow())
			{
				rows.add(new Row("", "above sellers: fills fast, margin " + fmt(GeTax.net(sellAt) - typed) + "/unit", Tone.WARN));
			}
		}
		guards(plugin, rows, c, st, q, nowEpochSec);
		return rows;
	}

	/** Offer-setup overlay, sell side. */
	static List<Row> sellSide(FlipCopilotPlugin plugin, ItemMeta meta, Quote q, int typed, int qty, long nowEpochSec)
	{
		List<Row> rows = new ArrayList<>();
		if (q == null || !q.isComplete())
		{
			rows.add(new Row("Market", "no quote yet", Tone.DIM));
			return rows;
		}
		ScanParams params = plugin.scanParams(plugin.budget());
		FlipCandidate c = FlipScanner.describe(meta, q, plugin.getSnapshot(), params, nowEpochSec);
		FlipCopilotPlugin.CachedStats st = plugin.statsFor(meta.getId());
		Position pos = plugin.tracker().position(meta.getId());
		int sellAt = c.getSell();
		boolean held = pos != null && pos.getQty() > 0;

		rows.add(new Row("Place @", fmt(sellAt) + " (buyers)", Tone.GOOD));
		rows.add(new Row("Buyers / sellers", fmt(q.getHigh()) + " / " + fmt(q.getLow()), Tone.TEXT));
		rows.add(new Row("Tax / net", fmt(GeTax.tax(sellAt)) + " / " + fmt(GeTax.net(sellAt)), Tone.DIM));
		if (held)
		{
			rows.add(new Row("Cost / break-even", fmt(pos.avgCost()) + " / " + fmt(pos.breakEven()), pos.breakEven() <= sellAt ? Tone.DIM : Tone.BAD));
		}
		else
		{
			rows.add(new Row("Break-even", "unknown (no tracked buy)", Tone.DIM));
		}
		rows.add(new Row("Dump @", fmt(q.getLow()) + " (instant)", Tone.DIM));
		rows.add(new Row("Buyers / hr", Ascii.compact(c.getSellSideVolPerHr()), c.getSellSideVolPerHr() < 20 ? Tone.WARN : Tone.DIM));
		if (qty > 0)
		{
			double min = c.getSellSideVolPerHr() > 0 ? qty / (c.getSellSideVolPerHr() * params.getVolShare()) * 60 : 999;
			rows.add(new Row("Fill est x" + qty, Fmt.min(min), min > params.getMaxCycleMin() ? Tone.WARN : Tone.DIM));
		}
		if (typed > 0)
		{
			String est = held ? String.format("  est %+,d", (long) Math.max(qty, 1) * (GeTax.net(typed) - pos.avgCost())) : "";
			Tone tone = held && typed < pos.breakEven() ? Tone.BAD : typed > q.getHigh() ? Tone.WARN : Tone.GOOD;
			rows.add(new Row("Your price", fmt(typed) + est, tone));
			if (held && typed < pos.breakEven())
			{
				rows.add(new Row("", "below break-even: you lose " + fmt(pos.breakEven() - typed) + "/unit", Tone.BAD));
			}
			else if (typed > q.getHigh())
			{
				rows.add(new Row("", "above buyers: will sit - relist at " + Fmt.clock(Instant.now().plusSeconds(plugin.rules().getSellWaitMin() * 60L)), Tone.BAD));
			}
			else if (typed % 50 == 0 && typed >= 100)
			{
				rows.add(new Row("", "multiple of 50: " + fmt(typed - 1) + " nets the same", Tone.WARN));
			}
		}
		guards(plugin, rows, c, st, q, nowEpochSec);
		return rows;
	}

	// ---------------- shared pieces ----------------

	private static void guards(FlipCopilotPlugin plugin, List<Row> rows, FlipCandidate c, FlipCopilotPlugin.CachedStats st, Quote q, long now)
	{
		ScanParams p = plugin.scanParams(1);
		long age = q.ageMinutes(now);
		if (age > p.getMaxAgeMin())
		{
			rows.add(new Row("Quote age", age + " min - stale", Tone.BAD));
		}
		if (Math.abs(c.getTrend24()) > p.getMaxTrend())
		{
			rows.add(new Row("24h move", Fmt.pct(c.getTrend24()) + " - not a flip", Tone.BAD));
		}
		else
		{
			rows.add(new Row("24h / 5m", Fmt.pct(c.getTrend24()) + " / " + Fmt.pct(c.getTrend5()), Tone.DIM));
		}
		if (st != null && st.getTrend7d() != null)
		{
			boolean slide = st.getTrend7d() < -p.getMaxTrend();
			rows.add(new Row("7d drift", Fmt.pct(st.getTrend7d()) + (slide ? " - hold risk" : ""), slide ? Tone.BAD : Tone.DIM));
		}
	}

	private static Row medianRow(FlipCopilotPlugin.CachedStats st)
	{
		if (st == null || st.getStats() == null)
		{
			return new Row("Med 3h margin", "loading...", Tone.DIM);
		}
		FlipScanner.RobustStats rs = st.getStats();
		Tone t = rs.median <= 0 ? Tone.BAD : rs.positive < 0.6 ? Tone.WARN : Tone.GOOD;
		return new Row("Med 3h margin", String.format("%,.0f (%.0f%% of windows +)", rs.median, rs.positive * 100), t);
	}

	private static Row limitRow(FlipCopilotPlugin plugin, ItemMeta meta)
	{
		Instant reset = plugin.limitResetAt(meta.getId());
		String v = (meta.getLimit() > 0 ? fmt(meta.getLimit()) + " / 4h" : "?") + (reset != null ? " (resets " + Fmt.clock(reset) + ")" : "");
		return new Row("Buy limit", v, reset != null ? Tone.WARN : Tone.DIM);
	}

	private static Tone marginTone(FlipCandidate c)
	{
		return c.getMargin() <= 0 ? Tone.BAD : c.getRoi() < 0.005 ? Tone.WARN : Tone.GOOD;
	}

	private static String age(long epochSec, long now)
	{
		long m = Math.max(0, now - epochSec) / 60;
		return m < 1 ? "<1m ago" : m < 120 ? m + "m ago" : (m / 60) + "h ago";
	}

	private static String fmt(long gp)
	{
		return String.format("%,d", gp);
	}
}
