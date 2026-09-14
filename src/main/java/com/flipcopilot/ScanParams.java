package com.flipcopilot;

import lombok.Builder;
import lombok.Value;

/** Scanner thresholds. Defaults mirror {@code osrs.py flip --robust --min-side-vol 5 --min-vol24 150}. */
@Value
@Builder(toBuilder = true)
public class ScanParams
{
	@Builder.Default long budget = 2_000_000L;
	@Builder.Default int minPrice = 100;
	@Builder.Default int maxAgeMin = 10;
	@Builder.Default long minSideVol1h = 5;
	@Builder.Default long minVol24h = 150;
	@Builder.Default double maxRoi = 0.15;
	@Builder.Default double volShare = 0.25;
	@Builder.Default int maxCycleMin = 30;
	@Builder.Default int minCycleMin = 15;
	@Builder.Default double maxTrend = 0.06;
	@Builder.Default boolean f2pOnly = false;
	/** How many top candidates get re-scored on the median 5m margin (0 = off). */
	@Builder.Default int robustTop = 25;
	@Builder.Default double minPositiveWindows = 0.6;
	@Builder.Default int top = 20;
}
