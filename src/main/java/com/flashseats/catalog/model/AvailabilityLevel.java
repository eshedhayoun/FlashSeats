package com.flashseats.catalog.model;

import com.flashseats.catalog.facade.CatalogFacade;

/**
 * How much of a tier is left, as a coarse bucket. Exact counts are never public (ADR-027): a live
 * number drives panic-buying and feeds scalpers. {@link #UNKNOWN} means the counter could not be
 * read. The client shows "checking availability", never sold out (ADR-040).
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
