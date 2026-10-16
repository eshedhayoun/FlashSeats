package com.flashseats.catalog.dto;

import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.catalog.model.EventStatus;
import java.time.Instant;
import java.util.List;

/**
 * One event as the operator sees it: any status, with exact live stock (ADR-080).
 *
 * <p>Exact counts are fine here and nowhere public — the buyer API shows buckets (ADR-027).
 * {@code prewarmed} is false while any tier has no counter; {@code deletable} says whether the
 * delete would be accepted right now.
 */
public record AdminEventResponse(
        long eventId,
        String title,
        String description,
        String venueName,
        Instant eventStartTime,
        Instant saleStartTime,
        Instant saleEndTime,
        EventStatus status,
        EventWindowStatus windowStatus,
        boolean prewarmed,
        boolean deletable,
        List<Tier> tiers) {

    /** {@code remaining} is null when the tier has no counter — unknown, never zero (ADR-040). */
    public record Tier(
            long tierId,
            String tierName,
            long priceCents,
            String currency,
            int totalCapacity,
            int maxPerOrder,
            Integer remaining) {}
}
