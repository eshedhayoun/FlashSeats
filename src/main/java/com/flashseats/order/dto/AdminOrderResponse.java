package com.flashseats.order.dto;

import com.flashseats.order.model.OrderStatus;
import java.time.Instant;
import java.util.List;

/**
 * An order as an operator needs to see it. <strong>Not {@code OrderReceiptResponse}</strong>: that
 * carries {@code receiptToken}, a 90-day bearer capability that must not land in terminal history or
 * logs (ADR-048). It adds what support asks for instead: payment attempts, the last failure, and
 * the hold.
 */
public record AdminOrderResponse(
        String orderNumber,
        OrderStatus status,
        String userEmail,
        long eventId,
        long totalAmountCents,
        String currency,
        String holdToken,
        int paymentAttempts,
        String failureReason,
        Instant createdAt,
        Instant updatedAt,
        List<OrderItemResponse> items) {}
