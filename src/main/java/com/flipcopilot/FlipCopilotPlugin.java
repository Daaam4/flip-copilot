package com.flipcopilot;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.inject.Inject;
import lombok.Getter;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.GrandExchangeOfferChanged;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.Notifier;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.ImageUtil;

/**
 * Flip Copilot.
 *
 * Simulates the copilot's flipping loop inside the client:
 * <ol>
 * <li>Scans the whole GE (wiki real-time prices) for margin flips that fit the budget and ranks them by gp/hour,
 *     re-scored on the median 3h margin so snapshot spreads can't lie.</li>
 * <li>While an offer is being created, overlays the optimal price for that side (buy at the instant-sell price,
 *     sell at the instant-buy price nudged off multiples of 50), break-even, tax, margin, flows and the buy limit,
 *     and grades the price you typed.</li>
 * <li>Runs the order clocks on every open slot (raise / relist / cancel / dump, with wall-clock deadlines) and
 *     tracks cost basis so break-even is real. Fills go to ~/.runelite/flip-copilot/ledger.csv.</li>
 * </ol>
 * Purely advisory: draws overlays and panels only, never sends input to the game.
 */
@Slf4j
@PluginDescriptor(
	name = "Flip Copilot",
	description = "Optimal GE buy/sell prices from live market data: flip scanner, offer-setup overlay, per-slot reprice clocks",
	tags = {"grand exchange", "ge", "flipping", "merch", "prices", "margin", "overlay"}
)
public class FlipCopilotPlugin extends Plugin
{
	private static final int GE_SLOTS = 8;
	private static final long LATEST_EVERY_SEC = 60;
	private static final long TS_CACHE_MS = 5 * 60 * 1000;
	private static final int ADVISE_EVERY_TICKS = 2;

	@Inject private Client client;
	@Inject private ClientThread clientThread;
	@Inject private ClientToolbar clientToolbar;
	@Inject private OverlayManager overlayManager;
	@Inject private ItemManager itemManager;
	@Inject private ConfigManager configManager;
	@Inject private Notifier notifier;
	@Inject private Gson gson;
	@Inject private ScheduledExecutorService executor;
	@Inject private FlipCopilotConfig config;
	@Inject private WikiPricesClient prices;
	@Inject private GeSetupOverlay setupOverlay;
	@Inject private SlotsOverlay slotsOverlay;

	private FlipCopilotPanel panel;
	private NavigationButton navButton;
	private OfferTracker tracker;

	private ScheduledFuture<?> scanTask;
	private ScheduledFuture<?> latestTask;
	private ScheduledFuture<?> saveTask;
	private final AtomicBoolean scanning = new AtomicBoolean();

	// ---- shared state (written on the executor, read on the client / swing threads) ----
	@Getter private volatile Map<Integer, ItemMeta> catalog = Collections.emptyMap();
	@Getter private volatile MarketSnapshot snapshot = MarketSnapshot.empty();
	@Getter private volatile Map<Integer, Quote> latest = Collections.emptyMap();
	@Getter private volatile Instant latestAt;
	@Getter private volatile List<FlipCandidate> candidates = Collections.emptyList();
	@Getter private volatile Instant scannedAt;
	@Getter private volatile String status = "starting";
	@Getter private volatile List<SlotAdvice> slotAdvice = Collections.emptyList();
	@Getter private volatile long budgetUsed;
	private final Map<Integer, CachedStats> statsCache = new ConcurrentHashMap<>();
	private final Map<String, Instant> notified = new HashMap<>();
	/** Coins seen in the inventory (volatile copy so the executor can read it). */
	private volatile long inventoryCoins = -1;
	private int tick;

	@Value
	static class CachedStats
	{
		long fetchedMs;
		FlipScanner.RobustStats stats;
		Double trend7d;
	}

	@Provides
	FlipCopilotConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(FlipCopilotConfig.class);
	}

	@Override
	protected void startUp()
	{
		Fmt.setZone(config.timezone());
		tracker = new OfferTracker(gson, prices.dataDir());
		panel = new FlipCopilotPanel(this, config, configManager);
		final BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("Flip Copilot")
			.icon(icon)
			.priority(4)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);
		overlayManager.add(setupOverlay);
		overlayManager.add(slotsOverlay);

		executor.execute(() ->
		{
			tracker.load();
			try
			{
				catalog = prices.mapping();
				status = catalog.size() + " items mapped";
			}
			catch (IOException ex)
			{
				status = "mapping failed: " + ex.getMessage();
				log.warn("flip-copilot: mapping fetch failed", ex);
			}
			refreshLatest();
			runScan();
		});
		scanTask = executor.scheduleWithFixedDelay(this::runScan, config.scanIntervalMin(), config.scanIntervalMin(), TimeUnit.MINUTES);
		latestTask = executor.scheduleWithFixedDelay(this::refreshLatest, LATEST_EVERY_SEC, LATEST_EVERY_SEC, TimeUnit.SECONDS);
		saveTask = executor.scheduleWithFixedDelay(() -> tracker.save(), 5, 5, TimeUnit.SECONDS);

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::syncOffers);
		}
		log.info("Flip Copilot started");
	}

	@Override
	protected void shutDown()
	{
		if (scanTask != null)
		{
			scanTask.cancel(false);
		}
		if (latestTask != null)
		{
			latestTask.cancel(false);
		}
		if (saveTask != null)
		{
			saveTask.cancel(false);
		}
		executor.execute(() -> tracker.save());
		overlayManager.remove(setupOverlay);
		overlayManager.remove(slotsOverlay);
		clientToolbar.removeNavigation(navButton);
		panel = null;
		slotAdvice = Collections.emptyList();
		log.info("Flip Copilot stopped");
	}

	// ---------------- events ----------------

	@Subscribe
	public void onConfigChanged(ConfigChanged e)
	{
		if (!FlipCopilotConfig.GROUP.equals(e.getGroup()))
		{
			return;
		}
		if ("timezone".equals(e.getKey()))
		{
			Fmt.setZone(config.timezone());
		}
		if ("scanIntervalMin".equals(e.getKey()) && scanTask != null)
		{
			scanTask.cancel(false);
			scanTask = executor.scheduleWithFixedDelay(this::runScan, config.scanIntervalMin(), config.scanIntervalMin(), TimeUnit.MINUTES);
		}
		FlipCopilotPanel p = panel;
		if (p != null)
		{
			p.syncFromConfig();
		}
		refreshPanel();
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged e)
	{
		if (e.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invokeLater(this::syncOffers);
		}
	}

	@Subscribe
	public void onGrandExchangeOfferChanged(GrandExchangeOfferChanged e)
	{
		GrandExchangeOffer o = e.getOffer();
		if (o.getState() == GrandExchangeOfferState.EMPTY && client.getGameState() != GameState.LOGGED_IN)
		{
			return; // the client clears offers while logging in / hopping; don't drop the clocks
		}
		String note = tracker.update(e.getSlot(), o, itemName(o.getItemId()), System.currentTimeMillis());
		if (note != null)
		{
			log.debug("flip-copilot: {}", note);
			if (config.notifyOnClock())
			{
				notifier.notify("Flip Copilot: " + note);
			}
		}
		advise();
	}

	@Subscribe
	public void onGameTick(GameTick e)
	{
		if (++tick % ADVISE_EVERY_TICKS == 0)
		{
			advise();
		}
	}

	// ---------------- offer tracking / advice (client thread) ----------------

	private void syncOffers()
	{
		GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return;
		}
		long now = System.currentTimeMillis();
		for (int i = 0; i < Math.min(GE_SLOTS, offers.length); i++)
		{
			GrandExchangeOffer o = offers[i];
			if (o != null)
			{
				tracker.update(i, o, itemName(o.getItemId()), now);
			}
		}
		advise();
	}

	private void advise()
	{
		Instant now = Instant.now();
		OfferAdvisor.Rules rules = rules();
		Map<Integer, Quote> q = latest;
		List<SlotAdvice> out = new ArrayList<>();
		for (TrackedOffer t : tracker.offers())
		{
			Advice a = OfferAdvisor.advise(t, q.get(t.getItemId()), tracker.position(t.getItemId()), rules, now);
			out.add(new SlotAdvice(t, a));
			maybeNotify(t, a, now);
		}
		if (!out.equals(slotAdvice))
		{
			slotAdvice = out;
			refreshPanel();
		}
	}

	private void maybeNotify(TrackedOffer t, Advice a, Instant now)
	{
		if (!config.notifyOnClock() || !(a.getLevel() == Advice.Level.DUE || a.getLevel() == Advice.Level.URGENT))
		{
			return;
		}
		String key = t.getSlot() + ":" + t.getItemId() + ":" + t.getPrice() + ":" + a.getLevel() + ":" + a.getAction();
		Instant last = notified.get(key);
		if (last != null && Duration.between(last, now).toMinutes() < 10)
		{
			return;
		}
		notified.put(key, now);
		notifier.notify("Flip Copilot: slot " + (t.getSlot() + 1) + " " + t.getName() + " - " + a.getAction());
	}

	OfferAdvisor.Rules rules()
	{
		return OfferAdvisor.Rules.builder()
			.buyWaitMin(config.buyWaitMin())
			.buyCancelMin(config.buyCancelMin())
			.sellWaitMin(config.sellWaitMin())
			.dumpMin(config.dumpMin())
			.bulkPriceThreshold(config.bulkPriceThreshold())
			.build();
	}

	OfferTracker tracker()
	{
		return tracker;
	}

	// ---------------- market data (executor thread) ----------------

	private void refreshLatest()
	{
		try
		{
			latest = prices.latest();
			latestAt = Instant.now();
		}
		catch (IOException ex)
		{
			status = "latest failed: " + ex.getMessage();
			log.debug("flip-copilot: latest fetch failed", ex);
		}
	}

	void requestScan()
	{
		executor.execute(this::runScan);
	}

	private void runScan()
	{
		if (!scanning.compareAndSet(false, true))
		{
			return;
		}
		try
		{
			if (catalog.isEmpty())
			{
				catalog = prices.mapping();
			}
			status = "scanning...";
			refreshPanel();
			long budget = budget();
			budgetUsed = budget;
			ScanParams p = scanParams(budget);
			MarketSnapshot snap = prices.snapshot();
			snapshot = snap;
			latest = snap.getLatest();
			latestAt = Instant.now();
			List<FlipCandidate> ranked = FlipScanner.scan(catalog, snap, p, snap.getFetchedEpochSec());
			if (p.getRobustTop() > 0)
			{
				ranked = FlipScanner.rescore(ranked, p, this::fetchTimeseries);
			}
			candidates = ranked.size() > p.getTop() ? new ArrayList<>(ranked.subList(0, p.getTop())) : ranked;
			scannedAt = Instant.now();
			status = String.format("%d picks for %s", candidates.size(), Fmt.gp(budget));
		}
		catch (Exception ex)
		{
			status = "scan failed: " + ex.getMessage();
			log.warn("flip-copilot: scan failed", ex);
		}
		finally
		{
			scanning.set(false);
			refreshPanel();
		}
	}

	private List<Bucket> fetchTimeseries(int itemId, String step)
	{
		try
		{
			List<Bucket> ts = prices.timeseries(itemId, step);
			if ("5m".equals(step))
			{
				FlipScanner.RobustStats st = FlipScanner.robustStats(ts);
				CachedStats prev = statsCache.get(itemId);
				statsCache.put(itemId, new CachedStats(System.currentTimeMillis(), st, prev != null ? prev.trend7d : null));
			}
			else if ("6h".equals(step))
			{
				CachedStats prev = statsCache.get(itemId);
				statsCache.put(itemId, new CachedStats(System.currentTimeMillis(), prev != null ? prev.stats : null, FlipScanner.drift7d(ts)));
			}
			return ts;
		}
		catch (IOException ex)
		{
			log.debug("flip-copilot: timeseries {} {} failed", itemId, step, ex);
			return null;
		}
	}

	/** Robust stats for one item; triggers a background fetch when missing / stale. Never blocks. */
	CachedStats statsFor(int itemId)
	{
		CachedStats c = statsCache.get(itemId);
		if (c == null || System.currentTimeMillis() - c.fetchedMs > TS_CACHE_MS)
		{
			if (c == null)
			{
				statsCache.put(itemId, new CachedStats(System.currentTimeMillis(), null, null)); // placeholder, avoids refetch storms
			}
			else
			{
				statsCache.put(itemId, new CachedStats(System.currentTimeMillis(), c.stats, c.trend7d));
			}
			executor.execute(() ->
			{
				fetchTimeseries(itemId, "5m");
				fetchTimeseries(itemId, "6h");
			});
		}
		return c;
	}

	ScanParams scanParams(long budget)
	{
		return ScanParams.builder()
			.budget(budget)
			.minPrice(config.minPrice())
			.maxAgeMin(config.maxQuoteAgeMin())
			.minSideVol1h(config.minSideVol1h())
			.minVol24h(config.minVol24h())
			.maxRoi(config.maxRoiPct() / 100.0)
			.maxTrend(config.maxTrendPct() / 100.0)
			.volShare(config.volSharePct() / 100.0)
			.maxCycleMin(config.maxCycleMin())
			.f2pOnly(config.f2pOnly())
			.robustTop(config.robustTop())
			.top(config.showTop())
			.build();
	}

	/** Budget: inventory coins when enabled and there are any, else the configured amount. */
	long budget()
	{
		if (config.useInventoryCoins() && inventoryCoins > 0)
		{
			return inventoryCoins;
		}
		return config.budget();
	}

	String budgetSource()
	{
		if (!config.useInventoryCoins())
		{
			return "configured";
		}
		return inventoryCoins > 0 ? "inventory coins" : "configured - no coins in inventory";
	}

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged e)
	{
		if (e.getContainerId() != InventoryID.INV)
		{
			return;
		}
		ItemContainer inv = e.getItemContainer();
		long coins = 0;
		if (inv != null)
		{
			for (Item it : inv.getItems())
			{
				if (it.getId() == ItemID.COINS)
				{
					coins += it.getQuantity();
				}
			}
		}
		inventoryCoins = coins;
	}

	// ---------------- helpers for overlays / panel ----------------

	String itemName(int itemId)
	{
		ItemMeta m = catalog.get(itemId);
		if (m != null)
		{
			return m.getName();
		}
		try
		{
			return itemManager.getItemComposition(itemId).getName();
		}
		catch (Exception ex)
		{
			return "item " + itemId;
		}
	}

	ItemMeta meta(int itemId)
	{
		ItemMeta m = catalog.get(itemId);
		if (m != null)
		{
			return m;
		}
		int limit = 0;
		try
		{
			net.runelite.client.game.ItemStats st = itemManager.getItemStats(itemId);
			if (st != null)
			{
				limit = st.getGeLimit();
			}
		}
		catch (Exception ignored)
		{
		}
		return new ItemMeta(itemId, itemName(itemId), limit, true);
	}

	/** Item selected in the GE offer-setup screen, or 0. Client thread only. */
	int setupItemId()
	{
		return client.getVarpValue(VarPlayerID.TRADINGPOST_SEARCH);
	}

	/** True when the offer being set up is a sell. Client thread only. */
	boolean setupIsSell()
	{
		return client.getVarbitValue(VarbitID.GE_NEWOFFER_TYPE) == 1;
	}

	int setupPrice()
	{
		return client.getVarbitValue(VarbitID.GE_NEWOFFER_PRICE);
	}

	int setupQuantity()
	{
		return client.getVarbitValue(VarbitID.GE_NEWOFFER_QUANTITY);
	}

	/** Buy-limit reset saved by the core Grand Exchange plugin, or null. */
	Instant limitResetAt(int itemId)
	{
		Instant t = configManager.getRSProfileConfiguration("grandexchange", "buylimit." + itemId, Instant.class);
		return t != null && t.isAfter(Instant.now()) ? t : null;
	}

	private void refreshPanel()
	{
		FlipCopilotPanel p = panel;
		if (p != null)
		{
			p.update(); // builds strings here, swaps them in on the EDT
		}
	}
}
