package com.flashseats.catalog.service;

import com.flashseats.catalog.model.TicketTier;

/**
 * An immutable snapshot of one {@code ticket_tiers} row.
 *
 * <p>Nothing in this system ever updates a tier after creation, which is what makes it safe to hold
 * for much longer than an event row. The TTL that still applies exists for rows inserted out of
 * band by {@code docker/seed/*.sql}, not for edits (ADR-051).
 */
public record TierRow(
        long id,
        long eventId,
        String tierName,
        long priceCents,
        String currency,
        int totalCapacity,
        int maxPerOrder) {

    static TierRow of(TicketTier tier) {
        return new TierRow(
                tier.getId(),
                tier.getEventId(),
                tier.getTierName(),
                tier.getPriceCents(),
                tier.getCurrency(),
                tier.getTotalCapacity(),
                tier.getMaxPerOrder());
    }
}
