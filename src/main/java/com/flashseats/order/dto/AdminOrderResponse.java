package com.flashseats.order.dto;

import com.flashseats.order.model.OrderStatus;
import java.time.Instant;
import java.util.List;

/**
 * An order as an operator needs to see it, answering "where is my ticket?".
 *
 * <p><strong>Deliberately not {@code OrderReceiptResponse}.</strong> That record carries
 * {@code receiptToken}, which is a bearer capability: it authorises reading the order from any
 * device, for ninety days, with no cookie. Returning it here would mint a durable impersonation link
 * into an operator's terminal history, their shell scrollback and any log that records response
 * bodies — for a question that never needed it. The buyer's own receipt path is unaffected; this one
 * simply has no business handing the capability out.
 *
 * <p>What it adds instead is what support actually asks for and the buyer's receipt has no reason to
 * show: how many payment attempts there were, why the last one failed, and the hold the order was
 * built from — the thread that connects an order to the seats and the sweeper.
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
