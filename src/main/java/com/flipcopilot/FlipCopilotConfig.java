package com.flipcopilot;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(FlipCopilotConfig.GROUP)
public interface FlipCopilotConfig extends Config
{
	String GROUP = "flip-copilot";

	@ConfigSection(name = "Budget & scanning", description = "What the scanner is allowed to spend and how often it runs", position = 0)
	String SCAN = "scan";

	@ConfigSection(name = "Scanner filters", description = "Mirrors osrs.py flip flags", position = 1)
	String FILTERS = "filters";

	@ConfigSection(name = "Order clocks", description = "When to reprice / cancel open offers", position = 2)
	String CLOCKS = "clocks";

	@ConfigSection(name = "Display", description = "Overlays and panel", position = 3)
	String DISPLAY = "display";

	// ---------------- scan ----------------

	@ConfigItem(keyName = "budget", name = "Budget (gp)", description = "Cash the scanner may allocate when 'Use inventory coins' is off", section = SCAN, position = 0)
	default int budget()
	{
		return 2_000_000;
	}

	@ConfigItem(keyName = "useInventoryCoins", name = "Use inventory coins", description = "Budget = coins in your inventory (falls back to Budget when not logged in)", section = SCAN, position = 1)
	default boolean useInventoryCoins()
	{
		return true;
	}

	@Range(min = 1, max = 60)
	@ConfigItem(keyName = "scanIntervalMin", name = "Scan every", description = "Minutes between full market scans (4 API calls + robust re-scoring)", section = SCAN, position = 2)
	@Units(Units.MINUTES)
	default int scanIntervalMin()
	{
		return 3;
	}

	@Range(min = 0, max = 60)
	@ConfigItem(keyName = "robustTop", name = "Robust re-score top N", description = "Re-rank this many candidates on the median 3h margin (2 API calls each). 0 = snapshot margins only", section = SCAN, position = 3)
	default int robustTop()
	{
		return 25;
	}

	@Range(min = 5, max = 40)
	@ConfigItem(keyName = "showTop", name = "Show top", description = "Rows in the panel table", section = SCAN, position = 4)
	default int showTop()
	{
		return 15;
	}

	@ConfigItem(keyName = "f2pOnly", name = "F2P items only", description = "Skip members items", section = SCAN, position = 5)
	default boolean f2pOnly()
	{
		return false;
	}

	// ---------------- filters ----------------

	@ConfigItem(keyName = "minPrice", name = "Min item price", description = "Ignore items cheaper than this (gp)", section = FILTERS, position = 0)
	default int minPrice()
	{
		return 100;
	}

	@Range(min = 1, max = 120)
	@ConfigItem(keyName = "maxQuoteAgeMin", name = "Max quote age", description = "Skip if either instant quote is older than this", section = FILTERS, position = 1)
	@Units(Units.MINUTES)
	default int maxQuoteAgeMin()
	{
		return 10;
	}

	@ConfigItem(keyName = "minSideVol1h", name = "Min 1h volume per side", description = "Both instant-buyers and instant-sellers in the last hour", section = FILTERS, position = 2)
	default int minSideVol1h()
	{
		return 5;
	}

	@ConfigItem(keyName = "minVol24h", name = "Min 24h volume", description = "Total units traded in 24h", section = FILTERS, position = 3)
	default int minVol24h()
	{
		return 150;
	}

	@Range(min = 1, max = 100)
	@ConfigItem(keyName = "maxRoiPct", name = "Max ROI %", description = "Drop margins above this (manipulated / one-off quotes)", section = FILTERS, position = 4)
	default int maxRoiPct()
	{
		return 15;
	}

	@Range(min = 1, max = 50)
	@ConfigItem(keyName = "maxTrendPct", name = "Max 24h / 7d move %", description = "Skip items spiking, crashing, or in a multi-day slide beyond this", section = FILTERS, position = 5)
	default int maxTrendPct()
	{
		return 6;
	}

	@Range(min = 5, max = 100)
	@ConfigItem(keyName = "volSharePct", name = "Volume share %", description = "Fraction of each side's hourly flow you capture at best price", section = FILTERS, position = 6)
	default int volSharePct()
	{
		return 25;
	}

	@Range(min = 5, max = 240)
	@ConfigItem(keyName = "maxCycleMin", name = "Max cycle", description = "Cap units so a full buy+sell cycle fits in this many minutes", section = FILTERS, position = 7)
	@Units(Units.MINUTES)
	default int maxCycleMin()
	{
		return 30;
	}

	// ---------------- clocks ----------------

	@Range(min = 1, max = 120)
	@ConfigItem(keyName = "buyWaitMin", name = "Buy: raise after", description = "0 filled after this -> +1 gp (bulk) / +0.5% (high value), never past the midpoint", section = CLOCKS, position = 0)
	@Units(Units.MINUTES)
	default int buyWaitMin()
	{
		return 15;
	}

	@Range(min = 1, max = 240)
	@ConfigItem(keyName = "buyCancelMin", name = "Buy: cancel after", description = "< 50% filled after this -> cancel the remainder and sell what filled", section = CLOCKS, position = 1)
	@Units(Units.MINUTES)
	default int buyCancelMin()
	{
		return 30;
	}

	@Range(min = 1, max = 120)
	@ConfigItem(keyName = "sellWaitMin", name = "Sell: step down after", description = "0 sold after this -> buyer price, then -1%, then break-even", section = CLOCKS, position = 2)
	@Units(Units.MINUTES)
	default int sellWaitMin()
	{
		return 20;
	}

	@Range(min = 1, max = 240)
	@ConfigItem(keyName = "dumpMin", name = "Sell: dump after", description = "Sitting at break-even for this long -> dump at the instant-sell price", section = CLOCKS, position = 3)
	@Units(Units.MINUTES)
	default int dumpMin()
	{
		return 30;
	}

	@ConfigItem(keyName = "bulkPriceThreshold", name = "Bulk price threshold", description = "Below this unit price a buy nudge is +1 gp; above it +0.5%", section = CLOCKS, position = 4)
	default int bulkPriceThreshold()
	{
		return 5_000;
	}

	@ConfigItem(keyName = "notifyOnClock", name = "Notify when a clock fires", description = "RuneLite notification when an offer needs repricing / cancelling / collecting", section = CLOCKS, position = 5)
	default boolean notifyOnClock()
	{
		return true;
	}

	// ---------------- display ----------------

	@ConfigItem(keyName = "setupOverlay", name = "Offer-setup overlay", description = "Show optimal buy/sell price while creating a GE offer", section = DISPLAY, position = 0)
	default boolean setupOverlay()
	{
		return true;
	}

	@ConfigItem(keyName = "slotsOverlay", name = "Slots overlay", description = "Show per-slot advice and deadlines on screen", section = DISPLAY, position = 1)
	default boolean slotsOverlay()
	{
		return true;
	}

	@ConfigItem(keyName = "slotsOverlayOnlyDue", name = "Slots overlay: only when due", description = "Hide the slots overlay while every offer is just waiting", section = DISPLAY, position = 2)
	default boolean slotsOverlayOnlyDue()
	{
		return false;
	}

	@ConfigItem(keyName = "menuLookup", name = "Right-click 'Flip lookup'", description = "Add a Flip lookup option to items in the inventory, bank and GE side panel", section = DISPLAY, position = 3)
	default boolean menuLookup()
	{
		return true;
	}

	@ConfigItem(keyName = "timezone", name = "Time zone", description = "IANA zone for clocks (e.g. Europe/Berlin). Blank = system default", section = DISPLAY, position = 4)
	default String timezone()
	{
		return "";
	}
}
