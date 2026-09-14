package com.flipcopilot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class FlipScannerTest
{
	private static final long NOW = 1_800_000_000L;
	private static final ItemMeta LAW = new ItemMeta(563, "Law rune", 18_000, false);
	private static final ItemMeta HELM = new ItemMeta(10828, "Helm of neitiznot", 70, true);

	private static MarketSnapshot snap(Map<Integer, Quote> latest, Map<Integer, Bucket> h1, Map<Integer, Bucket> h24)
	{
		return new MarketSnapshot(NOW, latest, Collections.emptyMap(), h1, h24);
	}

	private static Map<Integer, ItemMeta> catalog()
	{
		Map<Integer, ItemMeta> c = new HashMap<>();
		c.put(LAW.getId(), LAW);
		c.put(HELM.getId(), HELM);
		return c;
	}

	@Test
	public void lawRuneWheelIsScoredLikeTheCli()
	{
		// 14 Sep 08:51 UTC scan: buy 119 sell 123, 5,378 units on 640k, 15m cycle, ~43k gp/hr
		Map<Integer, Quote> latest = new HashMap<>();
		latest.put(LAW.getId(), new Quote(123, NOW - 60, 119, NOW - 120));
		Map<Integer, Bucket> h1 = new HashMap<>();
		h1.put(LAW.getId(), new Bucket(NOW - 3600, 123, 300_000, 119, 300_000));
		Map<Integer, Bucket> h24 = new HashMap<>();
		h24.put(LAW.getId(), new Bucket(NOW - 86_400, 121, 6_000_000, 119, 6_000_000));
		ScanParams p = ScanParams.builder().budget(640_000).robustTop(0).build();

		List<FlipCandidate> rows = FlipScanner.scan(catalog(), snap(latest, h1, h24), p, NOW);
		assertEquals(1, rows.size());
		FlipCandidate c = rows.get(0);
		assertEquals(119, c.getBuy());
		assertEquals(123, c.getSell());
		assertEquals(2, c.getMargin());
		assertEquals(5_378, c.getUnits()); // budget-bound: 640000 / 119
		assertEquals(5.16, c.getBuyMin(), 0.01);  // 5,378 / (250k/h * 25%)
		assertEquals(15, c.getCycleMin(), 0.01);  // 5.2 + 5.2 < 15 -> floor
		assertEquals(43_024, c.getGpPerHr(), 1);  // 2 gp x 5,378 / 0.25 h
		assertEquals(121, c.breakEven());
	}

	@Test
	public void guardsRejectStaleSpikingAndThinItems()
	{
		Map<Integer, Bucket> h1 = new HashMap<>();
		h1.put(HELM.getId(), new Bucket(NOW - 3600, 47_800, 150, 45_900, 130));
		Map<Integer, Bucket> h24 = new HashMap<>();
		h24.put(HELM.getId(), new Bucket(NOW - 86_400, 47_500, 3_000, 45_800, 2_900));
		ScanParams p = ScanParams.builder().budget(2_000_000).robustTop(0).build();

		Map<Integer, Quote> ok = new HashMap<>();
		ok.put(HELM.getId(), new Quote(48_167, NOW - 60, 45_775, NOW - 60));
		assertEquals(1, FlipScanner.scan(catalog(), snap(ok, h1, h24), p, NOW).size());

		Map<Integer, Quote> stale = new HashMap<>();
		stale.put(HELM.getId(), new Quote(48_167, NOW - 60, 45_775, NOW - 3600));
		assertTrue(FlipScanner.scan(catalog(), snap(stale, h1, h24), p, NOW).isEmpty());

		Map<Integer, Quote> spike = new HashMap<>();
		spike.put(HELM.getId(), new Quote(60_000, NOW - 60, 58_000, NOW - 60)); // +25% vs 24h
		assertTrue(FlipScanner.scan(catalog(), snap(spike, h1, h24), p, NOW).isEmpty());

		Map<Integer, Bucket> thin = new HashMap<>();
		thin.put(HELM.getId(), new Bucket(NOW - 3600, 47_800, 150, 45_900, 2)); // 2 instant-sellers/h
		assertTrue(FlipScanner.scan(catalog(), snap(ok, thin, h24), p, NOW).isEmpty());
	}

	@Test
	public void describeNeverRejects()
	{
		Quote q = new Quote(60_000, NOW - 60, 58_000, NOW - 7200);
		FlipCandidate c = FlipScanner.describe(HELM, q, snap(Collections.emptyMap(), Collections.emptyMap(), Collections.emptyMap()),
			ScanParams.builder().budget(1_000_000).build(), NOW);
		assertNotNull(c);
		assertEquals(59_999, c.getSell());
		assertEquals(120, c.getAgeMin());
	}

	@Test
	public void robustRescoreUsesMedianMarginAndBlocksDrift()
	{
		Map<Integer, Quote> latest = new HashMap<>();
		latest.put(LAW.getId(), new Quote(123, NOW - 60, 119, NOW - 120));
		Map<Integer, Bucket> h1 = new HashMap<>();
		h1.put(LAW.getId(), new Bucket(NOW - 3600, 123, 120_000, 119, 130_000));
		Map<Integer, Bucket> h24 = new HashMap<>();
		h24.put(LAW.getId(), new Bucket(NOW - 86_400, 121, 2_400_000, 119, 2_600_000));
		ScanParams p = ScanParams.builder().budget(640_000).robustTop(5).build();
		List<FlipCandidate> ranked = FlipScanner.scan(catalog(), snap(latest, h1, h24), p, NOW);

		// 36 windows: avgHigh 123 avgLow 120 -> margin 1 (tax 2)
		List<Bucket> ts5 = new ArrayList<>();
		for (int i = 0; i < 36; i++)
		{
			ts5.add(new Bucket(NOW - (36 - i) * 300L, 123, 100, 120, 100));
		}
		List<Bucket> flat6h = new ArrayList<>();
		for (int i = 0; i < 28; i++)
		{
			flat6h.add(new Bucket(NOW - (28 - i) * 21_600L, 122, 100, 120, 100));
		}
		List<FlipCandidate> out = FlipScanner.rescore(ranked, p, (id, step) -> "5m".equals(step) ? ts5 : flat6h);
		assertEquals(1, out.size());
		assertEquals(1.0, out.get(0).getMarginMed3h(), 0.001);
		assertEquals(1.0, out.get(0).getPositiveWindows(), 0.001);
		assertEquals(5_378.0, out.get(0).getProfit(), 0.001);

		// sunfire-style slide: last day 10% under the prior week -> blocked
		List<Bucket> slide = new ArrayList<>();
		for (int i = 0; i < 28; i++)
		{
			slide.add(new Bucket(NOW - (28 - i) * 21_600L, i >= 24 ? 108 : 120, 100, 118, 100));
		}
		ranked = FlipScanner.scan(catalog(), snap(latest, h1, h24), p, NOW);
		assertTrue(FlipScanner.rescore(ranked, p, (id, step) -> "5m".equals(step) ? ts5 : slide).isEmpty());

		assertNull(FlipScanner.robustStats(Collections.emptyList()));
	}
}
