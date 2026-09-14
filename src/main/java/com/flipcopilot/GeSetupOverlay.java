package com.flipcopilot;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.time.Instant;
import java.util.List;
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
		ItemMeta meta = plugin.meta(itemId);
		Quote q = plugin.getLatest().get(itemId);
		long now = Instant.now().getEpochSecond();

		panelComponent.getChildren().add(TitleComponent.builder()
			.text((sell ? "SELL " : "BUY ") + Ascii.of(meta.getName()))
			.color(sell ? ACCENT : GOOD)
			.build());

		List<ItemReport.Row> rows = sell
			? ItemReport.sellSide(plugin, meta, q, plugin.setupPrice(), plugin.setupQuantity(), now)
			: ItemReport.buySide(plugin, meta, q, plugin.setupPrice(), plugin.setupQuantity(), now);
		for (ItemReport.Row r : rows)
		{
			Color c = color(r.getTone());
			if (r.isNote())
			{
				panelComponent.getChildren().add(KeyValueComponent.builder().key("!").value(Ascii.of(r.getValue())).keyColor(c).valueColor(c).build());
			}
			else
			{
				panelComponent.getChildren().add(LineComponent.builder().left(r.getLabel()).leftColor(DIM).right(Ascii.of(r.getValue())).rightColor(c).build());
			}
		}
		return super.render(g);
	}

	static Color color(ItemReport.Tone t)
	{
		switch (t)
		{
			case GOOD:
				return GOOD;
			case WARN:
				return ACCENT;
			case BAD:
				return BAD;
			case DIM:
				return DIM;
			default:
				return Color.WHITE;
		}
	}
}
