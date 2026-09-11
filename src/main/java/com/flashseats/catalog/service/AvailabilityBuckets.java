package com.flashseats.catalog.service;

import com.flashseats.catalog.model.AvailabilityLevel;

/**
 * Maps an exact remaining count onto the coarse bucket the public API exposes (ADR-027).
 *
 * <p>The rounding lives here alone, so the landing page and the waiting room's
 * {@code tier-availability} frame can never disagree about whether a tier is "Limited".
 *
 * <p><strong>It takes the raw value, fault code included</strong> (ADR-040). The call site used to
 * clamp with {@code Math.max(remaining, 0)}, which turned {@link CatalogService#COUNTER_UNAVAILABLE}
 * into {@code 0} and published a missing counter as {@code SOLD_OUT} — exactly the substitution
 * ADR-004 forbids, on the one path ADR-035 did not cover. Passing the fault in and answering
 * {@link AvailabilityLevel#UNKNOWN} makes the distinction impossible to lose at a call site.
 */
public final class AvailabilityBuckets {

    private AvailabilityBuckets() {}

    public static AvailabilityLevel of(int remaining, int totalCapacity, int limitedThresholdPercent) {
        if (remaining == CatalogService.COUNTER_UNAVAILABLE) {
            return AvailabilityLevel.UNKNOWN;
        }
        if (remaining <= 0) {
            return AvailabilityLevel.SOLD_OUT;
        }
        long limitedAbove = (long) totalCapacity * limitedThresholdPercent / 100;
        return remaining <= limitedAbove ? AvailabilityLevel.LIMITED : AvailabilityLevel.PLENTY;
    }
}
