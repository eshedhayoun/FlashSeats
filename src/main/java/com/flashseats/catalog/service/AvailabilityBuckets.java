package com.flashseats.catalog.service;

import com.flashseats.catalog.model.AvailabilityLevel;

/**
 * Maps an exact remaining count onto the coarse bucket the public API exposes (ADR-027).
 *
 * <p>The rounding lives here alone, so the landing page and the waiting room's
 * {@code tier-availability} frame can never disagree about whether a tier is "Limited".
 */
public final class AvailabilityBuckets {

    private AvailabilityBuckets() {}

    public static AvailabilityLevel of(int remaining, int totalCapacity, int limitedThresholdPercent) {
        if (remaining <= 0) {
            return AvailabilityLevel.SOLD_OUT;
        }
        long limitedAbove = (long) totalCapacity * limitedThresholdPercent / 100;
        return remaining <= limitedAbove ? AvailabilityLevel.LIMITED : AvailabilityLevel.PLENTY;
    }
}
