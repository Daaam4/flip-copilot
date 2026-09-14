package com.flipcopilot;

import java.time.Duration;
import java.time.Instant;
import lombok.Builder;
import lombok.Value;

/**
 * The playbook's order clocks (flipping/README.md, method steps 5-8), as pure rules:
 *
 * <ul>
 * <li>BUY: 0 filled after {@code buyWaitMin} -> +1 gp (bulk) / +0.5% (high value), never past the midpoint.
 *     &lt; 50% filled after {@code buyCancelMin} -> cancel the remainder and sell what filled.
 *     Filled in seconds -> we overpaid; note it.</li>
 * <li>SELL: listed above the buyer price and 0 sold after {@code sellWaitMin} -> relist at the buyer price;
 *     then -1%; then break-even. At break-even for {@code dumpMin} -> dump at the instant-sell price.
 *     Never below break-even except the dump.</li>
 * </ul>
 */
public final class OfferAdvisor
{
	@Value
	@Builder(toBuilder = true)
	public static class Rules
	{
		@Builder.Default int buyWaitMin = 15;
		@Builder.Default int buyCancelMin = 30;
		@Builder.Default int sellWaitMin = 20;
		@Builder.Default int dumpMin = 30;
		/** Below this unit price a buy nudge is +1 gp; above it +0.5%. */
		@Builder.Default int bulkPriceThreshold = 5_000;
		/** A full fill faster than this means the price was too generous. */
		@Builder.Default int instantFillSec = 30;
	}

	private OfferAdvisor()
	{
	}

	public static Advice advise(TrackedOffer t, Quote q, Position pos, Rules r, Instant now)
	{
		if (t.isBuy())
		{
			return adviseBuy(t, q, pos, r, now);
		}
		return adviseSell(t, q, pos, r, now);
	}

	private static Advice adviseBuy(TrackedOffer t, Quote q, Position pos, Rules r, Instant now)
	{
		Instant placed = t.placedAt();
		long elapsedMin = Duration.between(placed, now).toMinutes();
		String market = q != null && q.isComplete() ? String.format("sellers@ %,d  buyers@ %,d", q.getLow(), q.getHigh()) : "no quote";

		if (t.isComplete())
		{
			String detail = market;
			Integer sellAt = null;
			if (q != null && q.isComplete())
			{
				sellAt = GeTax.preferredSell(q.getHigh());
				int be = GeTax.breakEven(t.getSold() > 0 ? t.getSpent() / t.getSold() : t.getPrice());
				long est = (long) t.getSold() * (GeTax.net(sellAt) - (t.getSold() > 0 ? t.getSpent() / t.getSold() : t.getPrice()));
				detail = String.format("%s  break-even %,d  est %+,d", market, be, est);
				long fillSec = t.getFirstFillAtMs() > 0 ? (t.getLastFillAtMs() - t.getPlacedAtMs()) / 1000 : -1;
				if (fillSec >= 0 && fillSec <= r.getInstantFillSec())
				{
					detail += String.format("  (filled in %ds: overpaid? next time 1 gp under)", fillSec);
				}
				if (sellAt < be)
				{
					return new Advice(Advice.Level.DONE, "Collect; buyers below break-even - hold, sell @ " + fmt(be), be, null, detail);
				}
			}
			return new Advice(Advice.Level.DONE, sellAt != null ? "Collect; SELL @ " + fmt(sellAt) : "Collect", sellAt, null, detail);
		}

		if (q == null || !q.isComplete())
		{
			return new Advice(Advice.Level.UNKNOWN, "Waiting (no quote)", null, placed.plus(Duration.ofMinutes(r.getBuyWaitMin())), market);
		}

		if (elapsedMin >= r.getBuyCancelMin() && t.filledFraction() < 0.5)
		{
			String action = t.getSold() > 0
				? String.format("Cancel remainder (%d/%d); SELL %d @ %s", t.getSold(), t.getTotal(), t.getSold(), fmt(GeTax.preferredSell(q.getHigh())))
				: "Cancel - starved " + elapsedMin + " min; redeploy";
			return new Advice(Advice.Level.URGENT, action, null, null, market);
		}

		Instant progress = lastProgress(t);
		long idleMin = Duration.between(progress, now).toMinutes();
		Instant nextClock = progress.plus(Duration.ofMinutes(r.getBuyWaitMin()));
		if (idleMin >= r.getBuyWaitMin())
		{
			int next = t.getPrice() < r.getBulkPriceThreshold() ? t.getPrice() + 1 : (int) Math.ceil(t.getPrice() * 1.005);
			int cap = q.mid();
			if (t.getPrice() >= cap)
			{
				return new Advice(Advice.Level.URGENT, "At the midpoint cap with 0 filled - cancel, redeploy", null, null, market);
			}
			if (t.getPrice() < q.getLow())
			{
				// sellers moved up: match them rather than creep
				next = Math.max(next, q.getLow());
			}
			next = Math.min(next, cap); // never past the midpoint
			Instant cancelAt = placed.plus(Duration.ofMinutes(r.getBuyCancelMin()));
			return new Advice(Advice.Level.DUE, "Raise to " + fmt(next), next, cancelAt, market + "  midpoint cap " + fmt(cap));
		}

		String detail = market;
		if (t.getPrice() < q.getLow())
		{
			detail += String.format("  (you're %,d under sellers)", q.getLow() - t.getPrice());
		}
		if (t.getSold() > 0)
		{
			detail += String.format("  %d/%d filled, last fill %s", t.getSold(), t.getTotal(), Fmt.clock(progress));
		}
		return new Advice(Advice.Level.WAIT, "Wait - raise at " + Fmt.clock(nextClock), null, nextClock, detail);
	}

	private static Advice adviseSell(TrackedOffer t, Quote q, Position pos, Rules r, Instant now)
	{
		Instant placed = t.placedAt();
		Integer breakEven = pos != null && pos.getQty() > 0 ? pos.breakEven() : null;
		int tax = GeTax.tax(t.getPrice());
		String market = q != null && q.isComplete() ? String.format("buyers@ %,d  sellers@ %,d", q.getHigh(), q.getLow()) : "no quote";
		String be = breakEven != null ? String.format("  break-even %,d", breakEven) : "  break-even unknown (no tracked buy)";
		String pnl = "";
		if (pos != null && pos.getQty() > 0)
		{
			long est = (long) (t.getTotal() - t.getSold()) * (t.getPrice() - tax - pos.avgCost());
			pnl = String.format("  est %+,d on remainder", est);
		}

		if (t.isComplete())
		{
			return new Advice(Advice.Level.DONE, "Collect coins - redeploy now", null, null, market + be);
		}
		if (q == null || !q.isComplete())
		{
			return new Advice(Advice.Level.UNKNOWN, "Waiting (no quote)", null, placed.plus(Duration.ofMinutes(r.getSellWaitMin())), market + be);
		}

		int buyerPrice = GeTax.preferredSell(q.getHigh());
		int floor = breakEven != null ? breakEven : 0;
		boolean atFloor = breakEven != null && t.getPrice() <= breakEven;

		Instant progress = lastProgress(t);
		long idleMin = Duration.between(progress, now).toMinutes();
		if (atFloor)
		{
			Instant dumpAt = progress.plus(Duration.ofMinutes(r.getDumpMin()));
			if (idleMin >= r.getDumpMin())
			{
				return new Advice(Advice.Level.URGENT, "Dump @ " + fmt(q.getLow()) + " (at break-even " + idleMin + " min)", q.getLow(), null, market + be + pnl);
			}
			return new Advice(Advice.Level.WAIT, "At break-even - dump at " + Fmt.clock(dumpAt) + " if 0 sold", null, dumpAt, market + be + pnl);
		}

		Instant nextClock = progress.plus(Duration.ofMinutes(r.getSellWaitMin()));
		boolean aboveBuyers = t.getPrice() > q.getHigh();
		if (idleMin >= r.getSellWaitMin())
		{
			// step ladder: buyer price -> -1% -> break-even; each step must be strictly below the current listing
			int step;
			if (aboveBuyers)
			{
				step = buyerPrice;
			}
			else
			{
				step = (int) Math.floor(t.getPrice() * 0.99);
			}
			step = Math.max(step, floor);
			if (step >= t.getPrice())
			{
				step = Math.max(t.getPrice() - 1, floor);
			}
			if (breakEven != null && step <= breakEven)
			{
				return new Advice(Advice.Level.DUE, "Relist @ break-even " + fmt(breakEven) + " - last step before dump", breakEven,
					now.plus(Duration.ofMinutes(r.getDumpMin())), market + be + pnl);
			}
			return new Advice(Advice.Level.DUE, "Relist @ " + fmt(step) + (aboveBuyers ? " (buyer price)" : " (-1%)"), step,
				now.plus(Duration.ofMinutes(r.getSellWaitMin())), market + be + pnl);
		}

		String detail = market + be + pnl;
		if (aboveBuyers)
		{
			detail = String.format("listed %,d above buyers - fishing; ", t.getPrice() - q.getHigh()) + detail;
		}
		String action = (aboveBuyers ? "Above buyers - relist @ " + fmt(buyerPrice) + " at " : "Wait - step down at ") + Fmt.clock(nextClock);
		return new Advice(Advice.Level.WAIT, action, aboveBuyers ? buyerPrice : null, nextClock, detail);
	}

	/** Clocks run from the last fill, or from placement when nothing has filled yet. */
	private static Instant lastProgress(TrackedOffer t)
	{
		return t.getLastFillAtMs() > t.getPlacedAtMs() ? Instant.ofEpochMilli(t.getLastFillAtMs()) : t.placedAt();
	}

	private static String fmt(int gp)
	{
		return String.format("%,d", gp);
	}
}
