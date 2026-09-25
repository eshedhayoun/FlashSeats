package com.flashseats.order.service;

import java.time.Instant;
import java.util.List;

/**
 * The fulfilment message, written into the outbox inside the order transaction. <strong>A complete,
 * self-contained snapshot</strong> (ADR-015): event title, venue, date and every line item, because
 * {@code notification} calls no facade. {@code items} is an array, one ticket page per tier. Not
 * shared with {@code notification}: the modules share a wire format, not a Java type.
 */
public record OutboxPayload(
        String eventType,
        String orderNumber,
        String receiptToken,
        String userEmail,
        long totalAmountCents,
        String currency,
        Instant confirmedAt,
        EventInfo event,
        List<Item> items) {

    public record EventInfo(long eventId, String title, String venueName, Instant startTime) {}

    public record Item(long tierId, String tierName, int quantity, long unitPriceCents) {}
}
