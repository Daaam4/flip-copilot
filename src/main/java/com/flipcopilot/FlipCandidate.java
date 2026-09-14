package com.flipcopilot;

import lombok.Builder;
import lombok.Data;

/** One scanner row. Mutable because robust re-scoring rewrites profit / gp-per-hour. */
@Data
@Builder
public class FlipCandidate
{
	int id;
	String name;
	boolean members;
	/** Place your BUY here (current instant-sell price). */
	int buy;
	/** Place your SELL here (current instant-buy price, nudged off multiples of 50). */
	int sell;
	int tax;
	/** Snapshot margin after tax. */
	int margin;
	double roi;
	int limit;
	int units;
	long capital;
	double profit;
	double buyMin;
	double sellMin;
	double cycleMin;
	double gpPerHr;
	double roiPerHr;
	long vol1h;
	long vol24h;
	double trend24;
	double trend5;
	/** Instant-sellers per hour who fill your BUY. */
	long buySideVolPerHr;
	/** Instant-buyers per hour who take your SELL. */
	long sellSideVolPerHr;
	long ageMin;
	// robust re-score (null when not computed)
	Double marginMed3h;
	Double positiveWindows;
	Double trend7d;

	public int breakEven()
	{
		return GeTax.breakEven(buy);
	}

	public double effectiveMargin()
	{
		return marginMed3h != null ? marginMed3h : margin;
	}
}
