package com.flashseats.catalog.model;

/**
 * How much of a tier is left, as a coarse bucket.
 *
 * <p>Exact counts are deliberately never exposed publicly (ADR-027): a live inventory number drives
 * panic-buying and hands scalpers a free feed. Exact values stay internal, for {@code hold} and for
 * metrics.
 */
public enum AvailabilityLevel {
    SOLD_OUT,
    LIMITED,
    PLENTY
}
