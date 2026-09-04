package com.flashseats.order.dto;

import com.flashseats.order.model.OrderStatus;
import java.time.Instant;
import java.util.List;

/**
 * The receipt.
 *
 * <p>{@code receiptToken} is returned so the client can build a durable link to this page — one that
 * survives a cleared cookie and works from the confirmation email.
 */
public record OrderReceiptResponse(
        String orderNumber,
        OrderStatus status,
        String userEmail,
        long totalAmountCents,
        String currency,
        String receiptToken,
        Instant createdAt,
        List<OrderItemResponse> items) {}
