package com.flashseats.order.service;

import com.flashseats.order.dto.OrderItemResponse;
import com.flashseats.order.dto.OrderReceiptResponse;
import com.flashseats.order.exception.OrderNotFoundException;
import com.flashseats.order.model.Order;
import com.flashseats.order.model.OrderStatus;
import com.flashseats.order.repository.OrderItemRepository;
import com.flashseats.order.repository.OrderRepository;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reads. No method here mutates anything. */
@Service
public class OrderQueryService {

    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final ReceiptTokens receiptTokens;

    public OrderQueryService(
            OrderRepository orders, OrderItemRepository items, ReceiptTokens receiptTokens) {
        this.orders = orders;
        this.items = items;
        this.receiptTokens = receiptTokens;
    }

    @Transactional(readOnly = true)
    public OrderReceiptResponse receiptFor(String orderNumber) {
        return toReceipt(orders.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new OrderNotFoundException(orderNumber)));
    }

    /**
     * Reads an order for a caller who presents either a matching session cookie or a valid receipt
     * token.
     *
     * <p>Two ways in, because the buyer needs both: the cookie covers the tab they bought in, and the
     * token covers the link in their email — a different device, weeks later, no session. The order
     * number alone authorises nothing (ADR-010).
     */
    @Transactional(readOnly = true)
    public OrderReceiptResponse readAuthorised(
            String orderNumber, String sessionId, String receiptToken) {

        Order order = orders.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new OrderNotFoundException(orderNumber));

        boolean ownSession = order.getUserSessionId().equals(sessionId);
        boolean validToken = receiptToken != null && receiptTokens.authorises(receiptToken, orderNumber);
        if (!ownSession && !validToken) {
            // 404 rather than 403: telling an unauthorised caller the order exists is itself a leak.
            throw new OrderNotFoundException(orderNumber);
        }
        return toReceipt(order);
    }

    /**
     * This session's most recent order for an event, whatever its status.
     *
     * <p>Feeds rehydration, which needs to answer "where is this buyer?" — and "they already bought"
     * is one of the answers (ADR-037). Restricting it to {@code PENDING} made a confirmed purchase
     * invisible the moment the page reloaded.
     */
    @Transactional(readOnly = true)
    public Optional<Order> findLatest(String sessionId, long eventId) {
        return orders.findFirstByUserSessionIdAndEventIdOrderByCreatedAtDesc(sessionId, eventId);
    }

    @Transactional(readOnly = true)
    public Optional<Order> findByOrderNumber(String orderNumber) {
        return orders.findByOrderNumber(orderNumber);
    }

    /**
     * The completed purchase for a hold, if there is one.
     *
     * <p>Checked at the very start of checkout. A successful purchase consumes its hold, so by the
     * time a client resubmits, the hold no longer exists — validating it first would answer a
     * duplicate submission with "your reservation expired" when in fact the buyer already owns the
     * seats. Replaying a completed operation must return its original result, not an error (global
     * standards §3).
     */
    @Transactional(readOnly = true)
    public Optional<OrderReceiptResponse> findConfirmedReceiptFor(String holdToken) {
        return orders.findByHoldToken(holdToken)
                .filter(order -> order.getStatus() == OrderStatus.CONFIRMED)
                .map(this::toReceipt);
    }

    private OrderReceiptResponse toReceipt(Order order) {
        List<OrderItemResponse> lines = items.findByOrderId(order.getId()).stream()
                .map(item -> new OrderItemResponse(
                        item.getEventId(),
                        item.getTierId(),
                        item.getTierName(),
                        item.getQuantity(),
                        item.getUnitPriceCents()))
                .toList();

        return new OrderReceiptResponse(
                order.getOrderNumber(),
                order.getStatus(),
                order.getUserEmail(),
                order.getTotalAmountCents(),
                order.getCurrency(),
                order.getReceiptToken(),
                order.getCreatedAt(),
                lines);
    }
}
