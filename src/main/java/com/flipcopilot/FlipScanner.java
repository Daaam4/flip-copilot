package com.flipcopilot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Port of {@code tools/osrs.py flip}.
 *
 * <p>Score = margin after tax x realistic units, where units is capped by the buy limit, the budget,
 * and the number of units that complete a full buy+sell cycle within {@link ScanParams#getMaxCycleMin()}.
 * To BUY at {@code low} we absorb the flow of instant-sellers (lowPriceVolume); to SELL at {@code high}
 * we absorb the flow of instant-buyers (highPriceVolume). Per-side hourly flow is the conservative of the
 * last completed 1h bucket and the 24h side volume / 24. Ranked by gp/hour.
 *
 * <p>Pure functions: no client, no IO. {@link #rescore} takes a fetcher so it can be unit-tested.
 */
public final class FlipScanner
{
	private FlipScanner()
	{
	}

	public static List<FlipCandidate> scan(Map<Integer, ItemMeta> catalog, MarketSnapshot snap, ScanParams p, long nowEpochSec)
	{
		List<FlipCandidate> rows = new ArrayList<>();
		for (Map.Entry<Integer, Quote> e : snap.getLatest().entrySet())
		{
			int id = e.getKey();
			ItemMeta meta = catalog.get(id);
			if (meta == null)
			{
				continue;
			}
			FlipCandidate c = score(meta, e.getValue(), snap, p, nowEpochSec);
			if (c != null)
			{
				rows.add(c);
			}
		}
		rows.sort(Comparator.comparingDouble(FlipCandidate::getGpPerHr).reversed());
		return rows;
	}

	/**
	 * Score one item; returns null when any guard rejects it. Used by the scan and by the offer-setup overlay
	 * (with guards relaxed via {@link #describe}).
	 */
	public static FlipCandidate score(ItemMeta meta, Quote q, MarketSnapshot snap, ScanParams p, long nowEpochSec)
	{
		if (q == null || !q.isComplete())
		{
			return null;
		}
		int high = q.getHigh();
		int low = q.getLow();
		if (low < p.getMinPrice() || low > p.getBudget())
		{
			return null;
		}
		long age = q.ageMinutes(nowEpochSec);
		if (age > p.getMaxAgeMin())
		{
			return null; // stale quote on one side
		}
		if (p.isF2pOnly() && meta.isMembers())
		{
			return null;
		}
		int limit = meta.getLimit();
		if (limit <= 0)
		{
			return null;
		}
		Bucket a1 = snap.bucket1h(meta.getId());
		Bucket a24 = snap.bucket24h(meta.getId());
		Bucket a5 = snap.bucket5m(meta.getId());
		if (a1.getHighVolume() < p.getMinSideVol1h() || a1.getLowVolume() < p.getMinSideVol1h())
		{
			return null; // need real two-sided flow in the last hour
		}
		if (a24.totalVolume() < p.getMinVol24h())
		{
			return null;
		}
		int tax = GeTax.tax(high);
		int margin = high - tax - low;
		if (margin <= 0)
		{
			return null;
		}
		double roi = margin / (double) low;
		if (roi > p.getMaxRoi())
		{
			return null; // almost certainly a manipulated / one-off quote
		}
		// sanity vs 1h averages: instant quotes shouldn't be wildly off the hour's trading range
		if (a1.getAvgHigh() != null && a1.getAvgLow() != null)
		{
			if (low > a1.getAvgHigh() * 1.03 || high < a1.getAvgLow() * 0.97)
			{
				return null;
			}
		}
		double midNow = (high + low) / 2.0;
		Integer avg24 = a24.anyPrice();
		double trend24 = avg24 != null ? midNow / avg24 - 1 : 0.0;
		Integer avg5 = a5.anyPrice();
		double trend5 = avg5 != null ? midNow / avg5 - 1 : 0.0;
		if (Math.abs(trend24) > p.getMaxTrend())
		{
			return null; // spiking or crashing: spreads during a move are not flips
		}
		return describe(meta, q, snap, p, nowEpochSec, trend24, trend5);
	}

	/** Turnover model + row assembly without the guards. Never null. */
	public static FlipCandidate describe(ItemMeta meta, Quote q, MarketSnapshot snap, ScanParams p, long nowEpochSec)
	{
		int high = q.getHigh();
		int low = q.getLow();
		double midNow = (high + low) / 2.0;
		Integer avg24 = snap.bucket24h(meta.getId()).anyPrice();
		Integer avg5 = snap.bucket5m(meta.getId()).anyPrice();
		double trend24 = avg24 != null ? midNow / avg24 - 1 : 0.0;
		double trend5 = avg5 != null ? midNow / avg5 - 1 : 0.0;
		return describe(meta, q, snap, p, nowEpochSec, trend24, trend5);
	}

	private static FlipCandidate describe(ItemMeta meta, Quote q, MarketSnapshot snap, ScanParams p, long nowEpochSec,
		double trend24, double trend5)
	{
		int high = q.getHigh();
		int low = q.getLow();
		Bucket a1 = snap.bucket1h(meta.getId());
		Bucket a24 = snap.bucket24h(meta.getId());
		int tax = GeTax.tax(high);
		int margin = high - tax - low;
		int limit = Math.max(meta.getLimit(), 0);

		double lowFlow = Math.min(a1.getLowVolume(), a24.getLowVolume() / 24.0);
		double highFlow = Math.min(a1.getHighVolume(), a24.getHighVolume() / 24.0);
		double buyRate = Math.max(1e-9, lowFlow * p.getVolShare());
		double sellRate = Math.max(1e-9, highFlow * p.getVolShare());
		double hoursPerUnit = 1 / buyRate + 1 / sellRate;
		long byBudget = low > 0 ? p.getBudget() / low : 0;
		long byTime = (long) ((p.getMaxCycleMin() / 60.0) / hoursPerUnit);
		int units = (int) Math.max(0, Math.min(Math.min(limit > 0 ? limit : Long.MAX_VALUE, byBudget), byTime));

		double buyMin = units / buyRate * 60;
		double sellMin = units / sellRate * 60;
		double cycleMin = Math.max(p.getMinCycleMin(), buyMin + sellMin);
		double profit = (double) margin * units;
		long capital = (long) low * units;
		double gpPerHr = profit / (cycleMin / 60);

		return FlipCandidate.builder()
			.id(meta.getId())
			.name(meta.getName())
			.members(meta.isMembers())
			.buy(low)
			.sell(GeTax.preferredSell(high))
			.tax(tax)
			.margin(margin)
			.roi(low > 0 ? margin / (double) low : 0)
			.limit(limit)
			.units(units)
			.capital(capital)
			.profit(profit)
			.buyMin(buyMin)
			.sellMin(sellMin)
			.cycleMin(cycleMin)
			.gpPerHr(gpPerHr)
			.roiPerHr(capital > 0 ? gpPerHr / capital : 0)
			.vol1h(a1.totalVolume())
			.vol24h(a24.totalVolume())
			.trend24(trend24)
			.trend5(trend5)
			.buySideVolPerHr((long) lowFlow)
			.sellSideVolPerHr((long) highFlow)
			.ageMin(q.ageMinutes(nowEpochSec))
			.build();
	}

	/**
	 * Re-score the top candidates on the MEDIAN 5m-window margin over the last 3h (snapshot margins lie),
	 * require >= 60% positive windows, block multi-day declines, and recompute profit / gp-per-hour.
	 *
	 * @param fetch (itemId, timestep) -> timeseries buckets oldest-first, or null on failure
	 */
	public static List<FlipCandidate> rescore(List<FlipCandidate> ranked, ScanParams p, BiFunction<Integer, String, List<Bucket>> fetch)
	{
		List<FlipCandidate> out = new ArrayList<>();
		int n = Math.min(p.getRobustTop(), ranked.size());
		for (int i = 0; i < n; i++)
		{
			FlipCandidate r = ranked.get(i);
			List<Bucket> ts = fetch.apply(r.getId(), "5m");
			if (ts == null)
			{
				continue;
			}
			RobustStats st = robustStats(ts);
			if (st == null || st.median <= 0 || st.positive < p.getMinPositiveWindows())
			{
				continue;
			}
			List<Bucket> t6 = fetch.apply(r.getId(), "6h");
			Double drift = drift7d(t6);
			if (drift != null && drift < -p.getMaxTrend())
			{
				continue; // steady multi-day decline: holding risk
			}
			r.setTrend7d(drift);
			r.setMarginMed3h(st.median);
			r.setPositiveWindows(st.positive);
			r.setProfit(st.median * r.getUnits());
			r.setGpPerHr(r.getProfit() / (r.getCycleMin() / 60));
			r.setRoiPerHr(r.getCapital() > 0 ? r.getGpPerHr() / r.getCapital() : 0);
			out.add(r);
		}
		out.sort(Comparator.comparingDouble(FlipCandidate::getGpPerHr).reversed());
		return out;
	}

	public static final class RobustStats
	{
		public final double median;
		public final double positive;
		public final int windows;

		RobustStats(double median, double positive, int windows)
		{
			this.median = median;
			this.positive = positive;
			this.windows = windows;
		}
	}

	/** Median realised margin over the last 36 five-minute windows (3h) and the share of positive windows. */
	public static RobustStats robustStats(List<Bucket> ts5m)
	{
		if (ts5m == null)
		{
			return null;
		}
		int from = Math.max(0, ts5m.size() - 36);
		List<Integer> ms = new ArrayList<>();
		for (int i = from; i < ts5m.size(); i++)
		{
			Integer m = ts5m.get(i).margin();
			if (m != null)
			{
				ms.add(m);
			}
		}
		if (ms.size() < 6)
		{
			return null;
		}
		double pos = ms.stream().filter(m -> m > 0).count() / (double) ms.size();
		Collections.sort(ms);
		int sz = ms.size();
		double med = sz % 2 == 1 ? ms.get(sz / 2) : (ms.get(sz / 2 - 1) + ms.get(sz / 2)) / 2.0;
		return new RobustStats(med, pos, sz);
	}

	/** Last 24h average vs the prior ~6 days on 6h buckets; null when there isn't enough history. */
	public static Double drift7d(List<Bucket> ts6h)
	{
		if (ts6h == null)
		{
			return null;
		}
		int from = Math.max(0, ts6h.size() - 28);
		List<Integer> vals = new ArrayList<>();
		for (int i = from; i < ts6h.size(); i++)
		{
			Integer v = ts6h.get(i).getAvgHigh();
			if (v != null)
			{
				vals.add(v);
			}
		}
		if (vals.size() < 12)
		{
			return null;
		}
		double recent = 0;
		for (int i = vals.size() - 4; i < vals.size(); i++)
		{
			recent += vals.get(i);
		}
		recent /= 4;
		double prior = 0;
		for (int i = 0; i < vals.size() - 4; i++)
		{
			prior += vals.get(i);
		}
		prior /= (vals.size() - 4);
		return prior > 0 ? recent / prior - 1 : null;
	}
}
