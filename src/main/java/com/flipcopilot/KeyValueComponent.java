package com.flipcopilot;

import com.google.common.base.MoreObjects;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;
import net.runelite.client.ui.overlay.components.ComponentConstants;
import net.runelite.client.ui.overlay.components.LayoutableRenderableEntity;
import net.runelite.client.ui.overlay.components.TextComponent;

/**
 * "key   value" row where the key sits in a fixed-width column and the value
 * flows left-aligned in the remaining width, wrapping as needed. An optional
 * tag (e.g. "(bank)") is drawn in its own colour after the value.
 *
 * Unlike {@link net.runelite.client.ui.overlay.components.LineComponent}, which
 * squeezes an overflowing right side into a third of the panel, this keeps long
 * values readable.
 */
@Setter
@Builder
class KeyValueComponent implements LayoutableRenderableEntity
{
	private String key;
	private String value;
	private String tag;

	/** Width of the key column in px; 0 = just wide enough for this key. */
	@Builder.Default
	private int keyWidth = 0;

	@Builder.Default
	private Color keyColor = Color.WHITE;

	@Builder.Default
	private Color valueColor = Color.WHITE;

	@Builder.Default
	private Color tagColor = Color.LIGHT_GRAY;

	@Builder.Default
	private Point preferredLocation = new Point();

	@Builder.Default
	private Dimension preferredSize = new Dimension(ComponentConstants.STANDARD_WIDTH, 0);

	@Builder.Default
	@Getter
	private final Rectangle bounds = new Rectangle();

	private static final int GAP = 6;

	@Override
	public Dimension render(Graphics2D g)
	{
		final String key = MoreObjects.firstNonNull(this.key, "");
		final String value = MoreObjects.firstNonNull(this.value, "");
		final String tag = MoreObjects.firstNonNull(this.tag, "");

		final FontMetrics fm = g.getFontMetrics();
		final int lineH = fm.getHeight();
		final int width = preferredSize.width;
		final int baseX = preferredLocation.x;
		final int baseY = preferredLocation.y + lineH;
		int y = baseY;

		final TextComponent text = new TextComponent();

		int col = keyWidth > 0 ? keyWidth : (key.isEmpty() ? 0 : fm.stringWidth(key) + GAP);
		int valueX = baseX + col;
		int valueW = width - col;

		if (!key.isEmpty())
		{
			text.setPosition(new Point(baseX, y));
			text.setText(key);
			text.setColor(keyColor);
			text.render(g);
		}

		// Not enough room beside the key: drop the value onto the next line, indented.
		if (valueW < width / 3)
		{
			if (!key.isEmpty())
			{
				y += lineH;
			}
			valueX = baseX + GAP;
			valueW = width - GAP;
		}

		final List<String> lines = wrap(value, valueW, fm);
		int lastLineW = 0;
		for (int i = 0; i < lines.size(); i++)
		{
			final String line = lines.get(i);
			text.setPosition(new Point(valueX, y));
			text.setText(line);
			text.setColor(valueColor);
			text.render(g);
			lastLineW = fm.stringWidth(line);
			if (i < lines.size() - 1)
			{
				y += lineH;
			}
		}

		if (!tag.isEmpty())
		{
			final int tagW = fm.stringWidth(tag);
			final int space = lines.isEmpty() ? 0 : fm.stringWidth(" ");
			int tagX;
			if (lastLineW + space + tagW <= valueW)
			{
				tagX = valueX + lastLineW + space;
			}
			else
			{
				if (!lines.isEmpty())
				{
					y += lineH;
				}
				tagX = valueX;
			}
			text.setPosition(new Point(tagX, y));
			text.setText(tag);
			text.setColor(tagColor);
			text.render(g);
		}

		y += lineH;

		final Dimension dim = new Dimension(width, y - baseY);
		bounds.setLocation(preferredLocation);
		bounds.setSize(dim);
		return dim;
	}

	private static List<String> wrap(String s, int maxWidth, FontMetrics fm)
	{
		final List<String> out = new ArrayList<>();
		if (s.isEmpty())
		{
			return out;
		}
		final String[] words = s.split(" ");
		final int spaceW = fm.stringWidth(" ");
		StringBuilder line = new StringBuilder(words[0]);
		int lineW = fm.stringWidth(words[0]);
		for (int i = 1; i < words.length; i++)
		{
			final int w = fm.stringWidth(words[i]);
			if (lineW + spaceW + w > maxWidth)
			{
				out.add(line.toString());
				line = new StringBuilder(words[i]);
				lineW = w;
			}
			else
			{
				line.append(' ').append(words[i]);
				lineW += spaceW + w;
			}
		}
		out.add(line.toString());
		return out;
	}
}
