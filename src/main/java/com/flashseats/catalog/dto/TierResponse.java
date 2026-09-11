package com.flashseats.catalog.dto;

import com.flashseats.catalog.model.AvailabilityLevel;

/**
 * One tier as the browse API exposes it.
 *
 * <p>{@code availability} is a bucket, never a number, and {@code maxPerOrder} is
 * server-authoritative — the UI renders whatever arrives here and hardcodes no limit of its own.
 */
public record TierResponse(
        long tierId,
        String tierName,
        long priceCents,
        String currency,
        int maxPerOrder,
        AvailabilityLevel availability) {}
