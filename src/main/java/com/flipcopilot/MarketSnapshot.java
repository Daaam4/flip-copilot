package com.flipcopilot;

import java.util.Collections;
import java.util.Map;
import lombok.Value;

/** Everything one scan needs, fetched at one instant. */
@Value
public class MarketSnapshot
{
	long fetchedEpochSec;
	Map<Integer, Quote> latest;
	Map<Integer, Bucket> m5;
	Map<Integer, Bucket> h1;
	Map<Integer, Bucket> h24;

	public static MarketSnapshot empty()
	{
		return new MarketSnapshot(0, Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap());
	}

	public Quote quote(int itemId)
	{
		return latest.get(itemId);
	}

	public Bucket bucket5m(int itemId)
	{
		return m5.getOrDefault(itemId, Bucket.empty());
	}

	public Bucket bucket1h(int itemId)
	{
		return h1.getOrDefault(itemId, Bucket.empty());
	}

	public Bucket bucket24h(int itemId)
	{
		return h24.getOrDefault(itemId, Bucket.empty());
	}
}
