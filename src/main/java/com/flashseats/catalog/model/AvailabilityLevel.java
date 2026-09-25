package com.flashseats.catalog.model;

import com.flashseats.catalog.facade.CatalogFacade;

/**
 * How much of a tier is left, as a coarse bucket.
 *
 * <p>Exact counts are deliberately never exposed publicly (ADR-027): a live inventory number drives
 * panic-buying and hands scalpers a free feed. Exact values stay internal, for {@code hold} and for
 * metrics.
 *
 * <p><strong>{@link #UNKNOWN} is not a bucket, it is the absence of one</strong> (ADR-040). "No
 * counter" and "no seats" are different facts, and a three-value enum could only express the second
 * — so a tier with no {@code tier_inventory} row was published to every visitor as {@code SOLD_OUT}.
 * That is ADR-004's failure mode on the browse path: it tells buyers a sale has ended because a row
 * is missing. The client renders it as "checking availability", never as sold out, and never
 * disables the tier.
 */
public enum AvailabilityLevel {
    SOLD_OUT,
    LIMITED,
    PLENTY,
    /** The counter could not be read. A fault to be repaired, never a sold-out tier (ADR-040). */
    UNKNOWN;

    /**
     * Maps an exact remaining count onto the public bucket (ADR-027), in one place so the landing
     * page and the waiting room can never disagree about "Limited". It takes the raw value, fault
     * code included: clamping {@code COUNTER_UNAVAILABLE} to {@code 0} would publish a missing
     * counter as {@code SOLD_OUT}, which ADR-004 forbids (ADR-040).
     */
    public static AvailabilityLevel of(int remaining, int totalCapacity, int limitedThresholdPercent) {
        if (remaining == CatalogFacade.COUNTER_UNAVAILABLE) {
            return UNKNOWN;
        }
        if (remaining <= 0) {
            return SOLD_OUT;
        }
        long limitedAbove = (long) totalCapacity * limitedThresholdPercent / 100;
        return remaining <= limitedAbove ? LIMITED : PLENTY;
    }
}
