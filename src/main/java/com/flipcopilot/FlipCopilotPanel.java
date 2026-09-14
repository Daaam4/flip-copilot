package com.flipcopilot;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.components.IconTextField;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.FontManager;
import net.runelite.client.ui.PluginPanel;
import static com.flipcopilot.Palette.H_ACCENT;
import static com.flipcopilot.Palette.H_BAD;
import static com.flipcopilot.Palette.H_DIM;
import static com.flipcopilot.Palette.H_GOOD;
import static com.flipcopilot.Palette.H_TEXT;
import static net.runelite.client.ui.PluginPanel.BORDER_OFFSET;
import static net.runelite.client.ui.PluginPanel.PANEL_WIDTH;
import static net.runelite.client.ui.PluginPanel.SCROLLBAR_WIDTH;

/**
 * Sidebar: budget controls, status, the open slots with their advice (the "do now" list, same content
 * as flipping/NEXT.md), what we hold, and the ranked flip table.
 *
 * Same layout rules as the Slayer Task Picker panel: everything is a wrapping HTML label because the
 * RuneScape font has no glyphs for arrows/bullets and plain text areas don't colour.
 */
class FlipCopilotPanel extends PluginPanel
{
	/** Section chrome: titled-border line + inner padding, both sides. */
	private static final int SECTION_INSETS = 2 * (1 + 5);
	/** Usable width for wrapped text: PluginPanel width minus scrollbar, panel offset and section insets. */
	private static final int TEXT_WIDTH = PANEL_WIDTH - SCROLLBAR_WIDTH - 2 * BORDER_OFFSET - SECTION_INSETS - 14; // = 170

	private final FlipCopilotPlugin plugin;
	private final FlipCopilotConfig config;
	private final ConfigManager configManager;

	private final JSpinner budgetSpinner = new JSpinner(new SpinnerNumberModel(2_000_000, 1_000, 2_000_000_000, 100_000));
	private final JCheckBox coinsBox = new JCheckBox("Use inventory coins");
	private final JCheckBox setupOverlayBox = new JCheckBox("Offer-setup overlay");
	private final JCheckBox slotsOverlayBox = new JCheckBox("Slots overlay");
	private final JCheckBox notifyBox = new JCheckBox("Notify when a clock fires");
	private final JButton scanButton = new JButton("Scan now");

	private static final int SEARCH_RESULTS = 8;

	private final IconTextField searchField = new IconTextField();
	private final JPanel resultsPanel = new JPanel();
	private final JLabel lookupLabel = html("");
	private final JLabel statusLabel = html("");
	private final JLabel slotsLabel = html("");
	private final JLabel positionsLabel = html("");
	private final JLabel picksLabel = html("");

	private boolean syncing;

	FlipCopilotPanel(FlipCopilotPlugin plugin, FlipCopilotConfig config, ConfigManager configManager)
	{
		super(true); // wrapped in a scroll pane - the picks table is long
		this.plugin = plugin;
		this.config = config;
		this.configManager = configManager;

		setLayout(new BorderLayout());
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel root = new JPanel();
		root.setLayout(new BoxLayout(root, BoxLayout.Y_AXIS));
		root.setBackground(ColorScheme.DARK_GRAY_COLOR);
		root.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0)); // PluginPanel already pads BORDER_OFFSET

		JLabel title = new JLabel("Flip Copilot");
		title.setFont(FontManager.getRunescapeBoldFont());
		title.setForeground(ColorScheme.BRAND_ORANGE);
		title.setAlignmentX(LEFT_ALIGNMENT);
		root.add(title);
		root.add(Box.createVerticalStrut(6));

		// ---- budget ----
		JPanel budget = section("Budget");
		budget.add(caption("Cash to allocate (gp)"));
		budget.add(full(budgetSpinner, 26));
		budget.add(box(coinsBox));
		budget.add(Box.createVerticalStrut(4));
		scanButton.setFont(FontManager.getRunescapeSmallFont());
		budget.add(full(scanButton, 24));
		budget.add(Box.createVerticalStrut(4));
		budget.add(html("<font color=" + H_DIM + ">Scanner = osrs.py flip --robust: margin after tax, per-side turnover, ranked by gp/hr, re-scored on the median 3h margin. Filters and clocks are in the plugin settings.</font>"));
		root.add(budget);

		// ---- display ----
		JPanel display = section("Display");
		for (JCheckBox b : new JCheckBox[]{setupOverlayBox, slotsOverlayBox, notifyBox})
		{
			display.add(box(b));
		}
		root.add(display);

		// ---- lookup ----
		JPanel lookup = section("Item lookup");
		lookup.add(caption("Search an item (or right-click one in-game)"));
		searchField.setIcon(IconTextField.Icon.SEARCH);
		searchField.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		searchField.setHoverBackgroundColor(ColorScheme.DARK_GRAY_HOVER_COLOR);
		searchField.addKeyListener(new KeyAdapter()
		{
			@Override
			public void keyReleased(KeyEvent e)
			{
				renderSearch();
			}
		});
		searchField.addClearListener(() ->
		{
			resultsPanel.removeAll();
			resultsPanel.setVisible(false);
			revalidate();
			repaint();
		});
		lookup.add(full(searchField, 30));
		resultsPanel.setLayout(new BoxLayout(resultsPanel, BoxLayout.Y_AXIS));
		resultsPanel.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		resultsPanel.setAlignmentX(LEFT_ALIGNMENT);
		resultsPanel.setVisible(false);
		lookup.add(resultsPanel);
		lookup.add(lookupLabel);
		root.add(lookup);

		// ---- status ----
		JPanel status = section("Status");
		status.add(statusLabel);
		root.add(status);

		// ---- slots ----
		JPanel slots = section("Slots - do now");
		slots.add(slotsLabel);
		root.add(slots);

		// ---- holding ----
		JPanel holding = section("Holding");
		holding.add(positionsLabel);
		root.add(holding);

		// ---- picks ----
		JPanel picks = section("Top flips (gp/hr)");
		picks.add(picksLabel);
		root.add(picks);

		add(root, BorderLayout.NORTH);

		wireConfig();
		syncFromConfig();
		update();
	}

	// ---------------- config wiring ----------------

	private void wireConfig()
	{
		budgetSpinner.addChangeListener(e -> set("budget", ((Number) budgetSpinner.getValue()).intValue()));
		coinsBox.addActionListener(e -> set("useInventoryCoins", coinsBox.isSelected()));
		setupOverlayBox.addActionListener(e -> set("setupOverlay", setupOverlayBox.isSelected()));
		slotsOverlayBox.addActionListener(e -> set("slotsOverlay", slotsOverlayBox.isSelected()));
		notifyBox.addActionListener(e -> set("notifyOnClock", notifyBox.isSelected()));
		scanButton.addActionListener(e -> plugin.requestScan());
	}

	private void set(String key, Object value)
	{
		if (!syncing)
		{
			configManager.setConfiguration(FlipCopilotConfig.GROUP, key, value);
		}
	}

	/** Pull current config values into the controls (called on ConfigChanged). */
	void syncFromConfig()
	{
		SwingUtilities.invokeLater(() ->
		{
			syncing = true;
			try
			{
				budgetSpinner.setValue(config.budget());
				coinsBox.setSelected(config.useInventoryCoins());
				setupOverlayBox.setSelected(config.setupOverlay());
				slotsOverlayBox.setSelected(config.slotsOverlay());
				notifyBox.setSelected(config.notifyOnClock());
			}
			finally
			{
				syncing = false;
			}
		});
	}

	// ---------------- live content ----------------

	/** Refresh status / slots / holding / picks from the plugin's current state. Safe from any thread. */
	void update()
	{
		final String status = formatStatus();
		final String slots = formatSlots(plugin.getSlotAdvice());
		final String holding = formatHolding(plugin.tracker() == null ? null : plugin.tracker().positions());
		final String picks = formatPicks(plugin.getCandidates());
		final String lookup = formatLookup(plugin.getLookupItemId());
		SwingUtilities.invokeLater(() ->
		{
			lookupLabel.setText(wrap(lookup));
			statusLabel.setText(wrap(status));
			slotsLabel.setText(wrap(slots));
			positionsLabel.setText(wrap(holding));
			picksLabel.setText(wrap(picks));
			revalidate();
			repaint();
		});
	}

	// ---------------- item lookup ----------------

	private void renderSearch()
	{
		List<ItemMeta> hits = plugin.search(searchField.getText(), SEARCH_RESULTS);
		resultsPanel.removeAll();
		for (ItemMeta m : hits)
		{
			Quote q = plugin.getLatest().get(m.getId());
			String price = q != null && q.isComplete() ? String.format("%,d / %,d", q.getLow(), q.getHigh()) : "-";
			JLabel row = new JLabel(wrap("<font color=" + H_TEXT + ">" + esc(m.getName()) + "</font> <font color=" + H_DIM + ">" + price + "</font>"));
			row.setAlignmentX(LEFT_ALIGNMENT);
			row.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
			row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			row.setOpaque(true);
			row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
			row.addMouseListener(new MouseAdapter()
			{
				@Override
				public void mouseClicked(MouseEvent e)
				{
					plugin.lookup(m.getId());
				}

				@Override
				public void mouseEntered(MouseEvent e)
				{
					row.setBackground(ColorScheme.DARK_GRAY_HOVER_COLOR);
				}

				@Override
				public void mouseExited(MouseEvent e)
				{
					row.setBackground(ColorScheme.DARKER_GRAY_COLOR);
				}
			});
			resultsPanel.add(row);
		}
		resultsPanel.setVisible(!hits.isEmpty());
		revalidate();
		repaint();
	}

	private String formatLookup(int itemId)
	{
		if (itemId <= 0)
		{
			return "<font color=" + H_DIM + ">Type a name above, or right-click an item in your inventory / bank and choose Flip lookup.</font>";
		}
		ItemMeta meta = plugin.meta(itemId);
		Quote q = plugin.getLatest().get(itemId);
		List<ItemReport.Row> rows = ItemReport.lookup(plugin, meta, q, Instant.now().getEpochSecond());
		StringBuilder sb = new StringBuilder();
		sb.append("<font color=").append(H_ACCENT).append("><b>").append(esc(meta.getName())).append("</b></font>");
		for (ItemReport.Row r : rows)
		{
			String c = tone(r.getTone());
			if (r.isNote())
			{
				sb.append("<br><font color=").append(c).append(">! ").append(esc(r.getValue())).append("</font>");
			}
			else
			{
				sb.append("<br><font color=").append(H_DIM).append(">").append(esc(r.getLabel())).append(":</font> <font color=").append(c).append(">")
					.append(esc(r.getValue())).append("</font>");
			}
		}
		return sb.toString();
	}

	private static String tone(ItemReport.Tone t)
	{
		switch (t)
		{
			case GOOD:
				return H_GOOD;
			case WARN:
				return H_ACCENT;
			case BAD:
				return H_BAD;
			case DIM:
				return H_DIM;
			default:
				return H_TEXT;
		}
	}

	private String formatStatus()
	{
		StringBuilder sb = new StringBuilder();
		sb.append("Budget: <font color=").append(H_TEXT).append("><b>").append(Fmt.gp(plugin.budget())).append("</b></font>")
			.append(" <font color=").append(H_DIM).append(">(").append(esc(plugin.budgetSource())).append(")</font><br>");
		long realized = plugin.tracker() != null ? plugin.tracker().realizedTotal() : 0;
		sb.append("Realized: <font color=").append(realized >= 0 ? H_GOOD : H_BAD).append("><b>")
			.append(String.format("%+,d", realized)).append("</b></font><br>");
		Instant la = plugin.getLatestAt();
		Instant sa = plugin.getScannedAt();
		sb.append("<font color=").append(H_DIM).append(">quotes ").append(la == null ? "-" : Fmt.clockSec(la))
			.append(" | scan ").append(sa == null ? "-" : Fmt.clockSec(sa)).append("<br>")
			.append(esc(plugin.getStatus())).append("</font>");
		return sb.toString();
	}

	private String formatSlots(List<SlotAdvice> rows)
	{
		if (rows == null || rows.isEmpty())
		{
			return "<font color=" + H_DIM + ">No open offers. Pick from the table below and place the buys; the clocks start automatically.</font>";
		}
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (SlotAdvice r : rows)
		{
			if (!first)
			{
				sb.append("<br>");
			}
			first = false;
			TrackedOffer t = r.getOffer();
			Advice a = r.getAdvice();
			boolean hot = a.needsAttention();
			sb.append("<font color=").append(hot ? H_ACCENT : H_TEXT).append(">")
				.append(hot ? "<b>&gt; " : "&nbsp;&nbsp;")
				.append(t.getSlot() + 1).append(' ').append(t.isBuy() ? "BUY" : "SELL").append(' ').append(esc(t.getName()))
				.append(hot ? "</b>" : "")
				.append("</font> <font color=").append(H_DIM).append(">")
				.append(String.format("%d/%d @ %,d", t.getSold(), t.getTotal(), t.getPrice()))
				.append("</font>");
			sb.append("<br><font color=").append(Palette.html(a.getLevel())).append(">&nbsp;&nbsp;&nbsp;")
				.append(esc(a.getAction())).append("</font>");
			if (a.getDeadline() != null && a.getLevel() != Advice.Level.WAIT)
			{
				sb.append(" <font color=").append(H_DIM).append(">(next ").append(Fmt.clock(a.getDeadline())).append(")</font>");
			}
			sb.append("<br><font color=").append(H_DIM).append(">&nbsp;&nbsp;&nbsp;")
				.append("placed ").append(Fmt.clock(t.placedAt())).append(" | ").append(esc(a.getDetail())).append("</font>");
		}
		return sb.toString();
	}

	private String formatHolding(List<Position> ps)
	{
		if (ps == null || ps.isEmpty())
		{
			return "<font color=" + H_DIM + ">Nothing held from tracked buys.</font>";
		}
		StringBuilder sb = new StringBuilder();
		boolean first = true;
		for (Position p : ps)
		{
			if (!first)
			{
				sb.append("<br>");
			}
			first = false;
			Quote q = plugin.getLatest().get(p.getItemId());
			sb.append("<font color=").append(H_TEXT).append(">").append(esc(p.getName())).append("</font>")
				.append(" <font color=").append(H_DIM).append(">")
				.append(String.format("x%,d @ %,d | be %,d", p.getQty(), p.avgCost(), p.breakEven())).append("</font>");
			if (q != null && q.isComplete())
			{
				boolean above = q.getHigh() >= p.breakEven();
				sb.append("<br><font color=").append(above ? H_GOOD : H_BAD).append(">&nbsp;&nbsp;&nbsp;buyers@ ")
					.append(String.format("%,d", q.getHigh()))
					.append(above ? String.format(" (%+,d)", (long) p.getQty() * (GeTax.net(q.getHigh()) - p.avgCost())) : " (under break-even)")
					.append("</font>");
			}
		}
		return sb.toString();
	}

	private String formatPicks(List<FlipCandidate> rows)
	{
		if (rows == null || rows.isEmpty())
		{
			return "<font color=" + H_DIM + ">No scan yet - press Scan now.</font>";
		}
		StringBuilder sb = new StringBuilder();
		int i = 0;
		for (FlipCandidate c : rows)
		{
			if (i++ > 0)
			{
				sb.append("<br>");
			}
			sb.append("<font color=").append(H_ACCENT).append(">").append(i).append(".</font> ")
				.append("<font color=").append(H_TEXT).append("><b>").append(esc(c.getName())).append("</b></font>");
			sb.append("<br><font color=").append(H_GOOD).append(">&nbsp;&nbsp;&nbsp;buy ")
				.append(String.format("%,d", c.getBuy())).append(" -> sell ").append(String.format("%,d", c.getSell())).append("</font>")
				.append(" <font color=").append(H_DIM).append(">")
				.append(String.format("m %,.0f%s", c.effectiveMargin(), c.getMarginMed3h() != null ? " med" : "")).append("</font>");
			sb.append("<br><font color=").append(H_DIM).append(">&nbsp;&nbsp;&nbsp;")
				.append(String.format("x%,d = %s | %s/hr | buy %s sell %s", c.getUnits(), Fmt.gp(c.getCapital()), Fmt.gp(c.getGpPerHr()),
					Fmt.min(c.getBuyMin()), Fmt.min(c.getSellMin())))
				.append("</font>");
			sb.append("<br><font color=").append(H_DIM).append(">&nbsp;&nbsp;&nbsp;")
				.append(String.format("flow %s in, %s out | 24h %s | lim %,d", Ascii.compact(c.getBuySideVolPerHr()), Ascii.compact(c.getSellSideVolPerHr()),
					Fmt.pct(c.getTrend24()), c.getLimit()))
				.append("</font>");
		}
		return sb.toString();
	}

	/** HTML-escape after converting to font-safe ASCII. */
	private static String esc(String s)
	{
		return Ascii.of(s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	private static String wrap(String body)
	{
		return "<html><body style='width:" + TEXT_WIDTH + "px; line-height:1.25'>" + body + "</body></html>";
	}

	// ---------------- swing helpers ----------------

	private static JPanel section(String name)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createEmptyBorder(0, 0, 10, 0),
			BorderFactory.createCompoundBorder(
				BorderFactory.createTitledBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR), name),
				BorderFactory.createEmptyBorder(4, 5, 8, 5))));
		p.setAlignmentX(LEFT_ALIGNMENT);
		return p;
	}

	private static JComponent full(JComponent c, int height)
	{
		c.setAlignmentX(LEFT_ALIGNMENT);
		c.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
		c.setPreferredSize(new Dimension(TEXT_WIDTH, height));
		return c;
	}

	private static JLabel caption(String text)
	{
		JLabel l = new JLabel(text);
		l.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		l.setAlignmentX(LEFT_ALIGNMENT);
		return l;
	}

	private static JCheckBox box(JCheckBox b)
	{
		b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		b.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		b.setAlignmentX(LEFT_ALIGNMENT);
		b.setHorizontalAlignment(JCheckBox.LEFT);
		b.setMaximumSize(new Dimension(Integer.MAX_VALUE, b.getPreferredSize().height + 4));
		return b;
	}

	private static JLabel html(String body)
	{
		JLabel l = new JLabel(wrap(body));
		l.setForeground(Color.WHITE);
		l.setAlignmentX(Component.LEFT_ALIGNMENT);
		l.setVerticalAlignment(JLabel.TOP);
		l.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		return l;
	}
}
