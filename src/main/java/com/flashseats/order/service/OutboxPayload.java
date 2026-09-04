package com.flashseats.order.service;

import java.time.Instant;
import java.util.List;

/**
 * The fulfilment message, as JSON, written into the outbox inside the order transaction.
 *
 * <p><strong>A complete, self-contained snapshot</strong> (ADR-015). {@code notification} calls no
 * facade and knows nothing about {@code catalog}, so everything needed to render a ticket — the
 * event's title, venue and date, and every line item — has to be here.
 *
 * <p>{@code items} is an <strong>array</strong>. An earlier design carried a single flat
 * {@code tierName}/{@code quantity} pair, which would have rendered the wrong ticket for any
 * multi-tier order.
 *
 * <p>This record is not shared with {@code notification}. The two modules are coupled by the wire
 * format, not by a Java type — which is what lets either one be deployed without the other.
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
