package com.flipcopilot;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class GeTaxTest
{
	@Test
	public void taxIsTwoPercentFlooredWithMinAndCap()
	{
		assertEquals(0, GeTax.tax(49));
		assertEquals(1, GeTax.tax(50));
		assertEquals(2, GeTax.tax(141));      // adamantite nails from the ledger
		assertEquals(280, GeTax.tax(14_020)); // torstol seed
		assertEquals(955, GeTax.tax(47_750)); // helm of neitiznot
		assertEquals(5_000_000, GeTax.tax(300_000_000));
	}

	@Test
	public void breakEvenCoversCostExactly()
	{
		for (int cost : new int[]{50, 99, 100, 119, 136, 1_295, 13_461, 45_775, 211_478, 1_000_000})
		{
			int be = GeTax.breakEven(cost);
			assertTrue("net at be covers cost", GeTax.net(be) >= cost);
			assertTrue("one gp lower would not", GeTax.net(be - 1) < cost);
		}
		// exact floored-tax maths; the CLI's ceil(buy / 0.98) is 1 gp conservative on these
		assertEquals(138, GeTax.breakEven(136));       // net(138) = 138 - 2 = 136 (CLI said 139)
		assertEquals(46_709, GeTax.breakEven(45_775)); // net = 46,709 - 934 = 45,775 (CLI said 46,710)
		assertEquals(1_321, GeTax.breakEven(1_295));   // net = 1,321 - 26 = 1,295
	}

	@Test
	public void preferredSellStepsOffMultiplesOfFifty()
	{
		assertEquals(49_999, GeTax.preferredSell(50_000));
		assertEquals(GeTax.net(50_000), GeTax.net(49_999));
		assertEquals(123, GeTax.preferredSell(123));
		assertEquals(50, GeTax.preferredSell(50));
	}
}
