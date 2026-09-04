package com.flashseats.notification.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.time.Instant;
import java.util.List;

/**
 * The fulfilment message, as this module reads it.
 *
 * <p>Deliberately a <em>separate</em> record from the one {@code order} writes, even though the two
 * describe the same JSON. Sharing a Java type would couple the modules at compile time and make the
 * wire format an implementation detail of whichever one happened to own the class — the point of an
 * asynchronous boundary is that neither has to know about the other.
 *
 * <p>{@code ignoreUnknown} so a future field added by the producer does not dead-letter every
 * message a consumer has not been redeployed to understand.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record OrderConfirmedPayload(
        String eventType,
        String orderNumber,
        String receiptToken,
        String userEmail,
        long totalAmountCents,
        String currency,
        Instant confirmedAt,
        EventInfo event,
        List<Item> items) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EventInfo(long eventId, String title, String venueName, Instant startTime) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Item(long tierId, String tierName, int quantity, long unitPriceCents) {}
}
