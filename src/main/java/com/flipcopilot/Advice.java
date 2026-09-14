package com.flipcopilot;

import java.time.Instant;
import lombok.Value;

/** What to do with one slot right now, and when the next clock fires. */
@Value
public class Advice
{
	public enum Level
	{
		/** Sitting correctly; wait for the clock. */
		WAIT,
		/** Clock fired: reprice / relist. */
		DUE,
		/** Cut it: cancel or dump. */
		URGENT,
		/** Filled: collect and move on. */
		DONE,
		/** Market data missing. */
		UNKNOWN
	}

	Level level;
	/** Short imperative, e.g. "Relist @ 47,750". */
	String action;
	/** Price the action refers to, or null. */
	Integer price;
	/** When the next step fires (null when the action is immediate). */
	Instant deadline;
	/** One-line context: market quotes, break-even, expected P&L. */
	String detail;

	public boolean needsAttention()
	{
		return level == Level.DUE || level == Level.URGENT || level == Level.DONE;
	}
}
