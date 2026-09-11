package com.flashseats.order.facade;

import java.time.Instant;

/** An order as other modules see it. Used by {@code saleflow} to rehydrate a page reload. */
public record OrderSummary(
        String orderNumber,
        String status,
        long eventId,
        long totalAmountCents,
        String currency,
        Instant createdAt) {}
