package com.flipcopilot;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.time.Instant;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import static com.flipcopilot.Palette.ACCENT;
import static com.flipcopilot.Palette.BAD;
import static com.flipcopilot.Palette.DIM;
import static com.flipcopilot.Palette.GOOD;

/**
 * While the GE offer-setup screen is open: the optimal price for this side of the trade, what the market
 * looks like right now, break-even / tax / margin, expected fill time for the quantity typed, the buy limit
 * and its reset time, and a grade of the price the player has entered.
 */
class GeSetupOverlay extends OverlayPanel
{
	private static final int DEFAULT_WIDTH = 260;
	private static final int MIN_WIDTH = 220;

	private final Client client;
	private final FlipCopilotPlugin plugin;
	private final FlipCopilotConfig config;

	@Inject
	GeSetupOverlay(Client client, FlipCopilotPlugin plugin, FlipCopilotConfig config)
	{
		this.client = client;
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.TOP_LEFT);
		setPriority(Overlay.PRIORITY_HIGH);
		addMenuEntry(MenuAction.RUNELITE_OVERLAY_CONFIG, "Configure", "Flip Copilot");
		setPreferredSize(new Dimension(DEFAULT_WIDTH, 0));
	}

	/** The overlay manager restores whatever width the user last dragged the panel to; clamp it so values don't wrap word-by-word. */
	@Override
	public Dimension getPreferredSize()
	{
		Dimension d = super.getPreferredSize();
		if (d == null || d.width < MIN_WIDTH)
		{
			return new Dimension(MIN_WIDTH, 0);
		}
		return d;
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!config.setupOverlay())
		{
			return null;
		}
		Widget setup = client.getWidget(InterfaceID.GeOffers.SETUP);
		if (setup == null || setup.isHidden())
		{
			return null;
		}
		int itemId = plugin.setupItemId();
		if (itemId <= 0)
		{
			return null;
		}
		boolean sell = plugin.setupIsSell();
		int typed = plugin.setupPrice();
		int qty = plugin.setupQuantity();
		ItemMeta meta = plugin.meta(itemId);
		Quote q = plugin.getLatest().get(itemId);
		long now = Instant.now().getEpochSecond();

		panelComponent.getChildren().add(TitleComponent.builder()
			.text((sell ? "SELL " : "BUY ") + Ascii.of(meta.getName()))
			.color(sell ? ACCENT : GOOD)
			.build());

		if (q == null || !q.isComplete())
		{
			line("Market", "no quote yet", DIM);
			return super.render(g);
		}

		ScanParams params = plugin.scanParams(plugin.budget());
		FlipCandidate c = FlipScanner.describe(meta, q, plugin.getSnapshot(), params, now);
		FlipCopilotPlugin.CachedStats st = plugin.statsFor(itemId);
		int sellAt = GeTax.preferredSell(q.getHigh());
		Position pos = plugin.tracker().position(itemId);

		if (sell)
		{
			renderSell(q, c, params, sellAt, pos, typed, qty);
		}
		else
		{
			renderBuy(meta, itemId, q, c, params, st, sellAt, typed, qty);
		}

		// ---- shared guards ----
		long age = q.ageMinutes(now);
		if (age > config.maxQuoteAgeMin())
		{
			line("Quote age", age + " min - stale", BAD);
		}
		if (Math.abs(c.getTrend24()) > config.maxTrendPct() / 100.0)
		{
			line("24h move", Fmt.pct(c.getTrend24()) + " - not a flip", BAD);
		}
		else
		{
			line("24h / 5m", Fmt.pct(c.getTrend24()) + " / " + Fmt.pct(c.getTrend5()), DIM);
		}
		if (st != null && st.getTrend7d() != null && st.getTrend7d() < -config.maxTrendPct() / 100.0)
		{
			line("7d drift", Fmt.pct(st.getTrend7d()) + " - hold risk", BAD);
		}
		return super.render(g);
	}

	private void renderSell(Quote q, FlipCandidate c, ScanParams params, int sellAt, Position pos, int typed, int qty)
	{
		line("Place @", fmt(sellAt) + " (buyers)", GOOD);
		line("Buyers / sellers", fmt(q.getHigh()) + " / " + fmt(q.getLow()), Color.WHITE);
		line("Tax / net", fmt(GeTax.tax(sellAt)) + " / " + fmt(GeTax.net(sellAt)), DIM);
		if (pos != null && pos.getQty() > 0)
		{
			line("Cost / break-even", fmt(pos.avgCost()) + " / " + fmt(pos.breakEven()), pos.breakEven() <= sellAt ? DIM : BAD);
		}
		else
		{
			line("Break-even", "unknown (no tracked buy)", DIM);
		}
		line("Dump @", fmt(q.getLow()) + " (instant)", DIM);
		line("Buyers / hr", Ascii.compact(c.getSellSideVolPerHr()), c.getSellSideVolPerHr() < 20 ? ACCENT : DIM);
		if (qty > 0)
		{
			double min = c.getSellSideVolPerHr() > 0 ? qty / (c.getSellSideVolPerHr() * params.getVolShare()) * 60 : 999;
			line("Fill est x" + qty, Fmt.min(min), min > config.maxCycleMin() ? ACCENT : DIM);
		}
		if (typed <= 0)
		{
			return;
		}
		String est = pos != null && pos.getQty() > 0 ? String.format("  est %+,d", (long) Math.max(qty, 1) * (GeTax.net(typed) - pos.avgCost())) : "";
		line("Your price", fmt(typed) + est, gradeSell(typed, q, pos));
		if (pos != null && pos.getQty() > 0 && typed < pos.breakEven())
		{
			note("below break-even: you lose " + fmt(pos.breakEven() - typed) + "/unit", BAD);
		}
		else if (typed > q.getHigh())
		{
			note("above buyers: will sit - relist at " + Fmt.clock(Instant.now().plusSeconds(config.sellWaitMin() * 60L)), BAD);
		}
		else if (typed % 50 == 0 && typed >= 100)
		{
			note("multiple of 50: " + fmt(typed - 1) + " nets the same", ACCENT);
		}
	}

	private void renderBuy(ItemMeta meta, int itemId, Quote q, FlipCandidate c, ScanParams params, FlipCopilotPlugin.CachedStats st,
		int sellAt, int typed, int qty)
	{
		line("Place @", fmt(q.getLow()) + " (sellers)", GOOD);
		line("Patient", fmt(q.getLow() - 1) + " (1 under, bulk)", DIM);
		line("Sellers / buyers", fmt(q.getLow()) + " / " + fmt(q.getHigh()), Color.WHITE);
		line("Sell target", fmt(sellAt) + " (be " + fmt(GeTax.breakEven(q.getLow())) + ")", DIM);
		Color mc = c.getMargin() <= 0 ? BAD : c.getRoi() < 0.005 ? ACCENT : GOOD;
		line("Margin / unit", fmt(c.getMargin()) + " (" + String.format("%.1f%%", c.getRoi() * 100) + ")", mc);
		if (st != null && st.getStats() != null)
		{
			FlipScanner.RobustStats rs = st.getStats();
			Color rc = rs.median <= 0 ? BAD : rs.positive < 0.6 ? ACCENT : GOOD;
			line("Med 3h margin", String.format("%,.0f (%.0f%% +)", rs.median, rs.positive * 100), rc);
		}
		else
		{
			line("Med 3h margin", "loading...", DIM);
		}
		line("Sellers / hr", Ascii.compact(c.getBuySideVolPerHr()), c.getBuySideVolPerHr() < 20 ? ACCENT : DIM);
		Instant reset = plugin.limitResetAt(itemId);
		line("Buy limit", (meta.getLimit() > 0 ? fmt(meta.getLimit()) : "?") + (reset != null ? " (resets " + Fmt.clock(reset) + ")" : ""),
			reset != null ? ACCENT : DIM);
		if (qty > 0)
		{
			double min = c.getBuySideVolPerHr() > 0 ? qty / (c.getBuySideVolPerHr() * params.getVolShare()) * 60 : 999;
			line("Fill est x" + qty, Fmt.min(min) + ", " + Fmt.gp((double) qty * q.getLow()), min > config.maxCycleMin() ? ACCENT : DIM);
		}
		if (typed <= 0)
		{
			return;
		}
		line("Your price", fmt(typed), gradeBuy(typed, q));
		if (typed > q.mid())
		{
			note("past the midpoint: overpaying by " + fmt(typed - q.getLow()) + "/unit", BAD);
		}
		else if (typed >= q.getHigh())
		{
			note("at the buyer price: zero margin", BAD);
		}
		else if (typed > q.getLow())
		{
			note("above sellers: fills fast, margin " + fmt(GeTax.net(sellAt) - typed) + "/unit", ACCENT);
		}
	}

	private static Color gradeBuy(int typed, Quote q)
	{
		if (typed <= q.getLow())
		{
			return GOOD;
		}
		if (typed <= q.mid())
		{
			return ACCENT;
		}
		return BAD;
	}

	private static Color gradeSell(int typed, Quote q, Position pos)
	{
		if (pos != null && pos.getQty() > 0 && typed < pos.breakEven())
		{
			return BAD;
		}
		if (typed > q.getHigh())
		{
			return ACCENT;
		}
		return GOOD;
	}

	private void line(String left, String right, Color c)
	{
		panelComponent.getChildren().add(LineComponent.builder().left(left).leftColor(DIM).right(right).rightColor(c).build());
	}

	/** Full-width wrapping remark under the previous line. */
	private void note(String text, Color c)
	{
		panelComponent.getChildren().add(KeyValueComponent.builder().key("!").value(Ascii.of(text)).keyColor(c).valueColor(c).build());
	}

	private static String fmt(long gp)
	{
		return String.format("%,d", gp);
	}
}
