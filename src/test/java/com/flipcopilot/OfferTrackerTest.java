package com.flipcopilot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import com.google.gson.Gson;
import java.io.File;
import java.util.List;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Test;

public class OfferTrackerTest
{
	private static GrandExchangeOffer ge(int item, GrandExchangeOfferState st, int price, int total, int sold, int spent)
	{
		return new GrandExchangeOffer()
		{
			@Override
			public int getQuantitySold()
			{
				return sold;
			}

			@Override
			public int getItemId()
			{
				return item;
			}

			@Override
			public int getTotalQuantity()
			{
				return total;
			}

			@Override
			public int getPrice()
			{
				return price;
			}

			@Override
			public int getSpent()
			{
				return spent;
			}

			@Override
			public GrandExchangeOfferState getState()
			{
				return st;
			}
		};
	}

	@Test
	public void roundTripTracksCostBasisAndRealizedProfit()
	{
		OfferTracker tr = new OfferTracker(new Gson(), new File(System.getProperty("java.io.tmpdir"), "flip-copilot-test"));
		long t = 1_000_000L;
		// torstol seed: buy 40 @ 13,461, sell 40 @ 14,020 -> +11,160 (ledger 2026-09-13)
		assertNull(tr.update(0, ge(5304, GrandExchangeOfferState.BUYING, 13_461, 40, 0, 0), "Torstol seed", t));
		assertNull(tr.update(0, ge(5304, GrandExchangeOfferState.BUYING, 13_461, 40, 10, 134_610), "Torstol seed", t + 60_000));
		String bought = tr.update(0, ge(5304, GrandExchangeOfferState.BOUGHT, 13_461, 40, 40, 538_440), "Torstol seed", t + 120_000);
		assertNotNull(bought);
		Position p = tr.position(5304);
		assertEquals(40, p.getQty());
		assertEquals(13_461, p.avgCost());
		assertEquals(13_735, p.breakEven());

		tr.update(0, ge(0, GrandExchangeOfferState.EMPTY, 0, 0, 0, 0), null, t + 130_000);
		assertNull(tr.offer(0));

		tr.update(1, ge(5304, GrandExchangeOfferState.SELLING, 14_020, 40, 0, 0), "Torstol seed", t + 200_000);
		String sold = tr.update(1, ge(5304, GrandExchangeOfferState.SOLD, 14_020, 40, 40, 560_800), "Torstol seed", t + 2_900_000);
		assertNotNull(sold);
		assertEquals(0, tr.position(5304).getQty());
		assertEquals(11_160, tr.position(5304).getRealized());
		assertEquals(11_160, tr.realizedTotal());

		List<String> ledger = tr.drainLedger();
		assertEquals(3, ledger.size());
		assertEquals("Torstol seed,sell,40,14020,280,549600", ledger.get(2).split(",", 2)[1].substring(0, "Torstol seed,sell,40,14020,280,549600".length()));
	}

	@Test
	public void relistRestartsTheClock()
	{
		OfferTracker tr = new OfferTracker(new Gson(), new File(System.getProperty("java.io.tmpdir"), "flip-copilot-test"));
		tr.update(5, ge(10828, GrandExchangeOfferState.SELLING, 48_167, 8, 0, 0), "Helm", 1000L);
		assertEquals(1000L, tr.offer(5).getPlacedAtMs());
		tr.update(5, ge(10828, GrandExchangeOfferState.SELLING, 48_167, 8, 0, 0), "Helm", 5000L);
		assertEquals(1000L, tr.offer(5).getPlacedAtMs()); // same offer, clock keeps running
		tr.update(5, ge(10828, GrandExchangeOfferState.SELLING, 47_750, 8, 0, 0), "Helm", 9000L);
		assertEquals(9000L, tr.offer(5).getPlacedAtMs()); // relisted at buyer price: new clock
	}

	@Test
	public void offersSeenWithFillsAlreadyAreNotInstantFills()
	{
		OfferTracker tr = new OfferTracker(new Gson(), new File(System.getProperty("java.io.tmpdir"), "flip-copilot-test"));
		assertNull(tr.update(2, ge(563, GrandExchangeOfferState.BOUGHT, 119, 3000, 3000, 357_000), "Law rune", 5000L));
		TrackedOffer t = tr.offer(2);
		assertEquals(3000, t.getSold());
		assertEquals(0, t.getFirstFillAtMs()); // no "filled in 0s" note
		assertEquals(3000, tr.position(563).getQty()); // but the cost basis is learned
		assertEquals(119, tr.position(563).avgCost());
		assertEquals(1, tr.drainLedger().size());
	}
}
