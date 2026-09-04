package com.flashseats.catalog.facade;

import java.time.Instant;

/**
 * Everything another module needs to know about a tier, in one read.
 *
 * <p>Deliberately wide: it carries the event's title, venue and start time so {@code order} can
 * write a <strong>complete, self-contained</strong> outbox payload and {@code notification} never
 * has to call back into {@code catalog} to render a ticket (ADR-015). {@code saleEndTime} is here so
 * {@code order} can apply the post-close checkout grace without a second lookup.
 */
public record TierSummary(
        long eventId,
        long tierId,
        String tierName,
        long priceCents,
        String currency,
        int maxPerOrder,
        String eventTitle,
        String venueName,
        Instant eventStartTime,
        Instant saleEndTime,
        EventWindowStatus windowStatus) {}
