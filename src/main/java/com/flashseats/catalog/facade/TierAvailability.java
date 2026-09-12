package com.flashseats.catalog.facade;

/** Public availability for one tier, as a bucket and never as an exact count (ADR-027). */
public record TierAvailability(long tierId, String level) {}
