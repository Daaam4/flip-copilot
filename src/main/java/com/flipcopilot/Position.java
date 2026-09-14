package com.flipcopilot;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/** Units we hold from GE buys and what they cost, so break-even is real, not guessed. Serialised to positions.json. */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Position
{
	int itemId;
	String name;
	long qty;
	long cost;
	long realized;

	public int avgCost()
	{
		return qty > 0 ? (int) Math.round(cost / (double) qty) : 0;
	}

	public int breakEven()
	{
		return qty > 0 ? GeTax.breakEven(avgCost()) : 0;
	}
}
