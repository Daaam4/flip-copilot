package com.flipcopilot;

/**
 * The RuneScape fonts used by overlays and the sidebar have no glyphs for
 * arrows, bullets or typographic dashes, so everything user-visible goes
 * through here first.
 */
final class Ascii
{
	private Ascii()
	{
	}

	static String of(String s)
	{
		if (s == null)
		{
			return "";
		}
		return s
			.replace("→", "->")
			.replace("↔", "<->")
			.replace("►", ">")
			.replace("•", "*")
			.replace("·", "|")
			.replace("—", "-")
			.replace("–", "-")
			.replace("≥", ">=")
			.replace("≤", "<=")
			.replace("×", "x")
			.replace("’", "'")
			.replace("‘", "'")
			.replace("“", "\"")
			.replace("”", "\"")
			.replace("…", "...");
	}

	/** 10800 -> "10.8k", 1140000 -> "1.14M", 950 -> "950". */
	static String compact(double n)
	{
		double a = Math.abs(n);
		if (a >= 1_000_000)
		{
			return trim(n / 1_000_000, 2) + "M";
		}
		if (a >= 1_000)
		{
			return trim(n / 1_000, 1) + "k";
		}
		return String.valueOf(Math.round(n));
	}

	private static String trim(double v, int decimals)
	{
		String s = String.format("%." + decimals + "f", v);
		if (s.contains("."))
		{
			s = s.replaceAll("0+$", "").replaceAll("\\.$", "");
		}
		return s;
	}
}
