package com.flipcopilot;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/** Formatting shared by the panel and overlays. Clocks are wall-clock local time (the player asked for that). */
public final class Fmt
{
	private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");
	private static final DateTimeFormatter HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss");
	private static volatile ZoneId zone = ZoneId.systemDefault();

	private Fmt()
	{
	}

	public static void setZone(String id)
	{
		try
		{
			zone = id == null || id.trim().isEmpty() ? ZoneId.systemDefault() : ZoneId.of(id.trim());
		}
		catch (Exception ex)
		{
			zone = ZoneId.systemDefault();
		}
	}

	public static ZoneId zone()
	{
		return zone;
	}

	public static String clock(Instant t)
	{
		return t == null ? "-" : HHMM.format(t.atZone(zone));
	}

	public static String clockSec(Instant t)
	{
		return t == null ? "-" : HHMMSS.format(t.atZone(zone));
	}

	public static String gp(double v)
	{
		double a = Math.abs(v);
		if (a >= 1_000_000_000)
		{
			return String.format("%.2fB", v / 1e9);
		}
		if (a >= 1_000_000)
		{
			return String.format("%.2fM", v / 1e6);
		}
		if (a >= 10_000)
		{
			return String.format("%.1fk", v / 1e3);
		}
		return String.format("%,d", Math.round(v));
	}

	public static String pct(double v)
	{
		return String.format("%+.1f%%", v * 100);
	}

	public static String min(double m)
	{
		return m < 1 ? "<1m" : String.format("%.0fm", m);
	}
}
