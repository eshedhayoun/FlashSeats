package com.flashseats.order.service;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.EventSummary;
import com.flashseats.order.dto.AdminOrderResponse;
import com.flashseats.order.dto.OrderItemResponse;
import com.flashseats.order.dto.OrderReceiptResponse;
import com.flashseats.order.exception.OrderErrors;
import com.flashseats.order.facade.OrderFacade;
import com.flashseats.order.facade.OrderSummary;
import com.flashseats.order.model.Order;
import com.flashseats.order.model.OrderStatus;
import com.flashseats.order.repository.OrderItemRepository;
import com.flashseats.order.repository.OrderRepository;
import com.flashseats.shared.ticket.TicketDocument;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads. No method here mutates anything.
 *
 * <p>This class <em>is</em> {@link OrderFacade}. Other modules see only that interface, because this
 * package is internal to the module and they may not name it. There is no separate delegating
 * implementation: one existed, held no logic, and only added a hop between the contract and the code
 * that honours it.
 */
@Service
public class OrderQueryService implements OrderFacade {

    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final ReceiptTokens receiptTokens;
    private final CatalogFacade catalog;

    public OrderQueryService(
            OrderRepository orders,
            OrderItemRepository items,
            ReceiptTokens receiptTokens,
            CatalogFacade catalog) {
        this.orders = orders;
        this.items = items;
        this.receiptTokens = receiptTokens;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public OrderReceiptResponse receiptFor(String orderNumber) {
        return toReceipt(orders.findByOrderNumber(orderNumber)
                .orElseThrow(() -> OrderErrors.orderNotFound(orderNumber)));
    }

    /**
     * An order read by an operator, who holds neither the cookie nor the receipt token. It is
     * authorised one layer up by {@code ROLE_ADMIN}, and returns {@link AdminOrderResponse}, which
     * withholds the receipt token.
     */
    @Transactional(readOnly = true)
    public AdminOrderResponse readForOperator(String orderNumber) {
        Order order = orders.findByOrderNumber(orderNumber)
                .orElseThrow(() -> OrderErrors.orderNotFound(orderNumber));

        return new AdminOrderResponse(
                order.getOrderNumber(),
                order.getStatus(),
                order.getUserEmail(),
                order.getEventId(),
                order.getTotalAmountCents(),
                order.getCurrency(),
                order.getHoldToken(),
                order.getPaymentAttempts(),
                order.getFailureReason(),
                order.getCreatedAt(),
                order.getUpdatedAt(),
                lineItemsOf(order));
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
                .orElseThrow(() -> OrderErrors.orderNotFound(orderNumber));

        boolean ownSession = order.getUserSessionId().equals(sessionId);
        boolean validToken = receiptToken != null && receiptTokens.authorises(receiptToken, orderNumber);
        if (!ownSession && !validToken) {
            // 404 rather than 403: telling an unauthorised caller the order exists is itself a leak.
            throw OrderErrors.orderNotFound(orderNumber);
        }
        return toReceipt(order);
    }

    /**
     * This session's most recent order for an event, whatever its status, because "they already bought"
     * is one answer rehydration needs (ADR-037). The status crosses as a string.
     */
    @Override
    @Transactional(readOnly = true)
    public Optional<OrderSummary> findLatestOrder(String sessionId, long eventId) {
        return orders.findFirstByUserSessionIdAndEventIdOrderByCreatedAtDesc(sessionId, eventId)
                .map(order -> new OrderSummary(
                        order.getOrderNumber(),
                        order.getStatus().name(),
                        order.getEventId(),
                        order.getTotalAmountCents(),
                        order.getCurrency(),
                        order.getCreatedAt()));
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
        return new OrderReceiptResponse(
                order.getOrderNumber(),
                order.getStatus(),
                order.getUserEmail(),
                order.getTotalAmountCents(),
                order.getCurrency(),
                order.getReceiptToken(),
                order.getCreatedAt(),
                lineItemsOf(order));
    }

    /**
     * What the ticket renderer needs, once the caller has proved the order is theirs (ADR-050).
     * Authorised exactly like {@link #readAuthorised}; an unauthorised caller gets {@code 404}, never
     * {@code 403}, so order numbers cannot be enumerated. Only a {@code CONFIRMED} order has a ticket.
     * Returns the document, not the bytes: rendering must not happen inside this transaction (ADR-023).
     */
    @Transactional(readOnly = true)
    public TicketDocument ticketDocumentFor(
            String orderNumber, String sessionId, String receiptToken) {

        Order order = orders.findByOrderNumber(orderNumber)
                .orElseThrow(() -> OrderErrors.orderNotFound(orderNumber));

        boolean ownSession = order.getUserSessionId().equals(sessionId);
        boolean validToken = receiptToken != null && receiptTokens.authorises(receiptToken, orderNumber);
        if (!ownSession && !validToken) {
            throw OrderErrors.orderNotFound(orderNumber);
        }
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            throw OrderErrors.ticketNotAvailable(order.getStatus());
        }

        // `order_items` snapshots tier_name at purchase, so renaming a tier later cannot rewrite an
        // issued ticket. The event's title, venue and start time are not this module's to hold and
        // come from the facade edge `order` already uses to price a checkout.
        EventSummary event = catalog.getEventSummary(order.getEventId());
        return new TicketDocument(
                order.getOrderNumber(),
                event.title(),
                event.venueName(),
                event.eventStartTime(),
                items.findByOrderId(order.getId()).stream()
                        .map(item -> new TicketDocument.Seat(item.getTierName(), item.getQuantity()))
                        .toList());
    }

    /** Shared by the buyer's receipt and the operator's view; the two differ everywhere else. */
    private List<OrderItemResponse> lineItemsOf(Order order) {
        return items.findByOrderId(order.getId()).stream()
                .map(item -> new OrderItemResponse(
                        item.getEventId(),
                        item.getTierId(),
                        item.getTierName(),
                        item.getQuantity(),
                        item.getUnitPriceCents()))
                .toList();
    }
}
