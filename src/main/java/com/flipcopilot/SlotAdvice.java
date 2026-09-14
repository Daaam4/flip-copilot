package com.flipcopilot;

import lombok.Value;

/** A tracked offer paired with the advice computed for it this tick. */
@Value
public class SlotAdvice
{
	TrackedOffer offer;
	Advice advice;
}
