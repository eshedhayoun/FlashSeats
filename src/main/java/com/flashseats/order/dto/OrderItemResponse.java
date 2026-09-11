package com.flashseats.order.dto;

/** One purchased line, as it appears on the receipt. */
public record OrderItemResponse(
        long eventId, long tierId, String tierName, int quantity, long unitPriceCents) {}
