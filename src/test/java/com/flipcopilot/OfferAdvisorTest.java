package com.flipcopilot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.time.Duration;
import java.time.Instant;
import org.junit.Test;

public class OfferAdvisorTest
{
	private static final Instant T0 = Instant.parse("2026-09-14T08:00:00Z");
	private static final OfferAdvisor.Rules R = OfferAdvisor.Rules.builder().build();

	private static TrackedOffer offer(boolean buy, int price, int total, int sold, Instant placed)
	{
		return new TrackedOffer(0, 10828, "Helm of neitiznot", buy, price, total, sold, sold * price, buy ? "BUYING" : "SELLING", placed.toEpochMilli(), 0, 0);
	}

	@Test
	public void sellAboveBuyersIsRelistedAtBuyerPriceAfterTheClock()
	{
		// helm sat 0/8 @ 48,167 while buyers traded at 47,750 -> relist at buyer price
		TrackedOffer t = offer(false, 48_167, 8, 0, T0);
		Quote q = new Quote(47_750, T0.getEpochSecond(), 46_900, T0.getEpochSecond());
		Position pos = new Position(10828, "Helm of neitiznot", 8, 8L * 45_775, 0);

		Advice early = OfferAdvisor.advise(t, q, pos, R, T0.plus(Duration.ofMinutes(5)));
		assertEquals(Advice.Level.WAIT, early.getLevel());
		assertEquals(T0.plus(Duration.ofMinutes(20)), early.getDeadline());

		Advice due = OfferAdvisor.advise(t, q, pos, R, T0.plus(Duration.ofMinutes(21)));
		assertEquals(Advice.Level.DUE, due.getLevel());
		assertEquals(Integer.valueOf(47_749), due.getPrice()); // buyer price, stepped off the multiple of 50
		assertTrue(due.getAction().contains("buyer price"));
	}

	@Test
	public void sellNeverStepsBelowBreakEvenThenDumps()
	{
		Position pos = new Position(10828, "Helm of neitiznot", 8, 8L * 45_775, 0); // be 46,709
		Quote q = new Quote(46_720, T0.getEpochSecond(), 46_000, T0.getEpochSecond());
		TrackedOffer t = offer(false, 46_720, 8, 0, T0); // at buyer price; -1% would be 46,252 < be
		Advice a = OfferAdvisor.advise(t, q, pos, R, T0.plus(Duration.ofMinutes(25)));
		assertEquals(Advice.Level.DUE, a.getLevel());
		assertEquals(Integer.valueOf(46_709), a.getPrice());

		TrackedOffer atBe = offer(false, 46_709, 8, 0, T0);
		assertEquals(Advice.Level.WAIT, OfferAdvisor.advise(atBe, q, pos, R, T0.plus(Duration.ofMinutes(10))).getLevel());
		Advice dump = OfferAdvisor.advise(atBe, q, pos, R, T0.plus(Duration.ofMinutes(31)));
		assertEquals(Advice.Level.URGENT, dump.getLevel());
		assertEquals(Integer.valueOf(46_000), dump.getPrice());
	}

	@Test
	public void buyClocksRaiseThenCancel()
	{
		TrackedOffer t = offer(true, 119, 3000, 0, T0);
		t.setName("Law rune");
		Quote q = new Quote(123, T0.getEpochSecond(), 119, T0.getEpochSecond());

		Advice wait = OfferAdvisor.advise(t, q, null, R, T0.plus(Duration.ofMinutes(10)));
		assertEquals(Advice.Level.WAIT, wait.getLevel());

		Advice raise = OfferAdvisor.advise(t, q, null, R, T0.plus(Duration.ofMinutes(16)));
		assertEquals(Advice.Level.DUE, raise.getLevel());
		assertEquals(Integer.valueOf(120), raise.getPrice()); // bulk: +1 gp
		assertEquals(T0.plus(Duration.ofMinutes(30)), raise.getDeadline());

		Advice cancel = OfferAdvisor.advise(t, q, null, R, T0.plus(Duration.ofMinutes(31)));
		assertEquals(Advice.Level.URGENT, cancel.getLevel());

		// high value: +0.5%, capped at the midpoint
		TrackedOffer hv = offer(true, 211_478, 3, 0, T0);
		Quote hq = new Quote(213_000, T0.getEpochSecond(), 211_478, T0.getEpochSecond());
		Advice r2 = OfferAdvisor.advise(hv, hq, null, R, T0.plus(Duration.ofMinutes(16)));
		assertEquals(Advice.Level.DUE, r2.getLevel());
		assertEquals(Integer.valueOf(212_239), r2.getPrice()); // +0.5% = 212,536 overshoots -> capped at the midpoint

		TrackedOffer atMid = offer(true, 212_239, 3, 0, T0);
		assertEquals(Advice.Level.URGENT, OfferAdvisor.advise(atMid, hq, null, R, T0.plus(Duration.ofMinutes(16))).getLevel());
	}

	@Test
	public void completedBuyTellsYouWhereToSell()
	{
		TrackedOffer t = offer(true, 45_775, 8, 8, T0);
		t.setFirstFillAtMs(T0.plusSeconds(10).toEpochMilli());
		t.setLastFillAtMs(T0.plusSeconds(12).toEpochMilli());
		Quote q = new Quote(48_000, T0.getEpochSecond(), 45_775, T0.getEpochSecond());
		Advice a = OfferAdvisor.advise(t, q, null, R, T0.plus(Duration.ofMinutes(1)));
		assertEquals(Advice.Level.DONE, a.getLevel());
		assertEquals(Integer.valueOf(47_999), a.getPrice()); // off the multiple of 50
		assertNull(a.getDeadline());
		assertTrue(a.getDetail().contains("overpaid"));
	}

	@Test
	public void partialSellStepsDownWhenTheRemainderStalls()
	{
		// irit unf: 300/335 sold at 08:24, remaining 35 stuck -> step at last fill + 20 min, not placement + 20
		Position pos = new Position(10828, "Irit potion (unf)", 335, 335L * 1_770, 0); // be 1,806
		TrackedOffer t = offer(false, 1_808, 335, 300, T0);
		t.setLastFillAtMs(T0.plus(Duration.ofMinutes(9)).toEpochMilli());
		Quote q = new Quote(1_800, T0.getEpochSecond(), 1_785, T0.getEpochSecond());

		Advice atPlaced20 = OfferAdvisor.advise(t, q, pos, R, T0.plus(Duration.ofMinutes(21)));
		assertEquals(Advice.Level.WAIT, atPlaced20.getLevel());
		assertEquals(T0.plus(Duration.ofMinutes(29)), atPlaced20.getDeadline());

		Advice due = OfferAdvisor.advise(t, q, pos, R, T0.plus(Duration.ofMinutes(30)));
		assertEquals(Advice.Level.DUE, due.getLevel());
		assertEquals(Integer.valueOf(1_806), due.getPrice()); // buyer price 1,800 < break-even -> relist at break-even
	}
}
