package com.flipcopilot;

import lombok.Value;

/** One averaged window (/5m, /1h, /24h or a /timeseries point). Prices may be null when no trade happened. */
@Value
public class Bucket
{
	long timestamp;
	Integer avgHigh;
	long highVolume;
	Integer avgLow;
	long lowVolume;

	public long totalVolume()
	{
		return highVolume + lowVolume;
	}

	/** Realised flip margin in this window: what instant-buyers paid minus tax minus what instant-sellers got. */
	public Integer margin()
	{
		if (avgHigh == null || avgLow == null)
		{
			return null;
		}
		return avgHigh - GeTax.tax(avgHigh) - avgLow;
	}

	/** Best available price for trend maths. */
	public Integer anyPrice()
	{
		return avgHigh != null ? avgHigh : avgLow;
	}

	public static Bucket empty()
	{
		return new Bucket(0, null, 0, null, 0);
	}
}
