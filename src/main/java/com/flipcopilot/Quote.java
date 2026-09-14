package com.flipcopilot;

import lombok.Value;

/** One row of prices.runescape.wiki /latest: instant-buy (high) and instant-sell (low) with unix timestamps. */
@Value
public class Quote
{
	int high;
	long highTime;
	int low;
	long lowTime;

	public boolean isComplete()
	{
		return high > 0 && low > 0;
	}

	/** Minutes since the older of the two quotes. */
	public long ageMinutes(long nowEpochSec)
	{
		return Math.max(nowEpochSec - highTime, nowEpochSec - lowTime) / 60;
	}

	public int mid()
	{
		return (high + low) / 2;
	}
}
