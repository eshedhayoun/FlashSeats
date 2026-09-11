package com.flashseats.catalog.model;

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
    UNKNOWN
}
