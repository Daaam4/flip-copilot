package com.flipcopilot;

import java.awt.Color;

/** One colour set for overlays (AWT) and the sidebar (HTML), matching the Slayer Task Picker plugin. */
final class Palette
{
	static final Color GOOD = new Color(120, 255, 120);
	static final Color BAD = new Color(255, 120, 120);
	static final Color DIM = new Color(170, 170, 170);
	static final Color ACCENT = new Color(255, 220, 120);
	static final Color SECTION = new Color(255, 200, 90);

	static final String H_GOOD = "#78ff78";
	static final String H_BAD = "#ff7878";
	static final String H_DIM = "#b4b4b4";
	static final String H_ACCENT = "#ffdc78";
	static final String H_TEXT = "#ffffff";

	private Palette()
	{
	}

	static Color of(Advice.Level level)
	{
		switch (level)
		{
			case DUE:
				return ACCENT;
			case URGENT:
				return BAD;
			case DONE:
				return GOOD;
			case UNKNOWN:
				return DIM;
			default:
				return Color.WHITE;
		}
	}

	static String html(Advice.Level level)
	{
		switch (level)
		{
			case DUE:
				return H_ACCENT;
			case URGENT:
				return H_BAD;
			case DONE:
				return H_GOOD;
			case UNKNOWN:
				return H_DIM;
			default:
				return H_TEXT;
		}
	}
}
