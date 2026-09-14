package com.flipcopilot;

import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A GE slot as we saw it, plus the timing the playbook's clocks need. Serialised to offers.json. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TrackedOffer
{
	int slot;
	int itemId;
	String name;
	boolean buy;
	int price;
	int total;
	int sold;
	int spent;
	String state;
	/** When this offer (item+price+total) first appeared in the slot: every relist restarts the clock. */
	long placedAtMs;
	/** Last time {@code sold} increased. */
	long lastFillAtMs;
	/** First time {@code sold} increased. */
	long firstFillAtMs;

	public Instant placedAt()
	{
		return Instant.ofEpochMilli(placedAtMs);
	}

	public boolean isComplete()
	{
		return total > 0 && sold >= total;
	}

	public double filledFraction()
	{
		return total > 0 ? sold / (double) total : 0;
	}

	public boolean sameOffer(int itemId, boolean buy, int price, int total)
	{
		return this.itemId == itemId && this.buy == buy && this.price == price && this.total == total;
	}
}
