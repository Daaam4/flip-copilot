package com.flipcopilot;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics2D;
import java.util.List;
import javax.inject.Inject;
import net.runelite.api.MenuAction;
import net.runelite.client.ui.overlay.OverlayPanel;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.components.LineComponent;
import net.runelite.client.ui.overlay.components.TitleComponent;
import static com.flipcopilot.Palette.ACCENT;
import static com.flipcopilot.Palette.DIM;

/** On-screen "DO NOW" list: one entry per open slot with its advice and the next clock (the NEXT.md of the client). */
class SlotsOverlay extends OverlayPanel
{
	private static final int DEFAULT_WIDTH = 300;
	private static final int MIN_WIDTH = 240;
	private static final int KEY_GAP = 8;

	private final FlipCopilotPlugin plugin;
	private final FlipCopilotConfig config;

	/** Key-column width for the advice rows, cached per font so we don't measure every frame. */
	private Font measuredFont;
	private int keyWidth;

	@Inject
	SlotsOverlay(FlipCopilotPlugin plugin, FlipCopilotConfig config)
	{
		this.plugin = plugin;
		this.config = config;
		setPosition(OverlayPosition.ABOVE_CHATBOX_RIGHT);
		addMenuEntry(MenuAction.RUNELITE_OVERLAY_CONFIG, "Configure", "Flip Copilot");
		setPreferredSize(new Dimension(DEFAULT_WIDTH, 0));
	}

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
		if (!config.slotsOverlay())
		{
			return null;
		}
		List<SlotAdvice> rows = plugin.getSlotAdvice();
		if (rows.isEmpty())
		{
			return null;
		}
		boolean anyDue = false;
		for (SlotAdvice r : rows)
		{
			anyDue |= r.getAdvice().needsAttention();
		}
		if (config.slotsOverlayOnlyDue() && !anyDue)
		{
			return null;
		}
		measureKeys(g);

		panelComponent.getChildren().add(TitleComponent.builder()
			.text(anyDue ? "Flip Copilot - do now" : "Flip Copilot - waiting")
			.color(anyDue ? ACCENT : DIM)
			.build());
		for (SlotAdvice r : rows)
		{
			TrackedOffer t = r.getOffer();
			Advice a = r.getAdvice();
			Color c = a.needsAttention() ? Palette.of(a.getLevel()) : Color.WHITE;
			panelComponent.getChildren().add(LineComponent.builder()
				.left((a.needsAttention() ? "> " : "  ") + (t.getSlot() + 1) + " " + (t.isBuy() ? "BUY " : "SELL ") + Ascii.of(t.getName()))
				.right(String.format("%d/%d @ %,d", t.getSold(), t.getTotal(), t.getPrice()))
				.leftColor(c)
				.rightColor(DIM)
				.build());
			String tag = a.getDeadline() != null && a.getLevel() == Advice.Level.WAIT ? "" : a.getDeadline() != null ? "(next " + Fmt.clock(a.getDeadline()) + ")" : "";
			panelComponent.getChildren().add(KeyValueComponent.builder()
				.key("    ").keyWidth(keyWidth)
				.value(Ascii.of(a.getAction()))
				.tag(tag)
				.valueColor(Palette.of(a.getLevel()))
				.tagColor(DIM)
				.build());
		}
		return super.render(g);
	}

	private void measureKeys(Graphics2D g)
	{
		Font font = g.getFont();
		if (font.equals(measuredFont))
		{
			return;
		}
		keyWidth = g.getFontMetrics(font).stringWidth("> 8 ") + KEY_GAP;
		measuredFont = font;
	}
}
