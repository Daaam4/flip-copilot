package com.flipcopilot;

/**
 * Grand Exchange sell tax (2% floored, none under 50 gp, capped at 5M per item) and the
 * derived helpers the flipping playbook uses: break-even sell price and "never list at an
 * exact multiple of 50" (tax floors, so 1 gp under nets the same coins).
 */
public final class GeTax
{
	public static final int MIN_PRICE = 50;
	public static final int CAP = 5_000_000;

	private GeTax()
	{
	}

	/** Tax per unit at a given sell price. 2% == price / 50 exactly, so no floating point. */
	public static int tax(int price)
	{
		if (price < MIN_PRICE)
		{
			return 0;
		}
		return Math.min(price / 50, CAP);
	}

	/** Coins received per unit after tax. */
	public static int net(int price)
	{
		return price - tax(price);
	}

	/** Smallest sell price whose net covers {@code cost}. */
	public static int breakEven(int cost)
	{
		if (cost < MIN_PRICE)
		{
			return cost;
		}
		// net(p) = p - floor(p/50) >= cost  ->  p ~= ceil(cost * 50 / 49); then fix rounding
		int p = (int) Math.max(cost, (cost * 50L + 48) / 49);
		while (net(p) < cost)
		{
			p++;
		}
		while (p > MIN_PRICE && net(p - 1) >= cost)
		{
			p--;
		}
		return p;
	}

	/** Selling at a multiple of 50 nets the same as 1 gp under it; prefer the lower listing. */
	public static int preferredSell(int price)
	{
		if (price >= MIN_PRICE + 50 && price % 50 == 0 && net(price - 1) == net(price))
		{
			return price - 1;
		}
		return price;
	}
}
