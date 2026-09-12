package com.flashseats.order.service;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.EventSummary;
import com.flashseats.order.dto.AdminOrderResponse;
import com.flashseats.order.dto.OrderItemResponse;
import com.flashseats.order.dto.OrderReceiptResponse;
import com.flashseats.order.exception.OrderNotFoundException;
import com.flashseats.order.exception.TicketNotAvailableException;
import com.flashseats.order.model.Order;
import com.flashseats.order.model.OrderStatus;
import com.flashseats.order.repository.OrderItemRepository;
import com.flashseats.order.repository.OrderRepository;
import com.flashseats.shared.ticket.TicketDocument;
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
                .orElseThrow(() -> new OrderNotFoundException(orderNumber)));
    }

    /**
     * An order read by an operator, who has neither the buyer's cookie nor their receipt token.
     *
     * <p>{@link #readAuthorised} cannot serve this: its whole job is to refuse a caller presenting
     * neither, so for an operator it always throws. The authorisation happens one layer up instead,
     * at {@code ROLE_ADMIN} on {@code /api/v1/admin/**} — which is the right place for it, since
     * "this person operates the system" is not a fact about the order.
     *
     * <p>Returns {@link AdminOrderResponse}, which withholds the receipt token. See that record.
     */
    @Transactional(readOnly = true)
    public AdminOrderResponse readForOperator(String orderNumber) {
        Order order = orders.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new OrderNotFoundException(orderNumber));

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
     * Everything the ticket renderer needs, once the caller has proved the order is theirs (ADR-050).
     *
     * <p><strong>Authorisation is the same two ways in as {@link #readAuthorised}</strong> — a
     * matching session cookie or a valid receipt token — and it sits beside it deliberately, so the
     * two cannot drift. An unauthorised caller gets {@code 404}, never {@code 403}: confirming the
     * order exists is itself a leak and would make order numbers enumerable.
     *
     * <p><strong>Only a {@code CONFIRMED} order has a ticket.</strong> Rendering for any other status
     * would mint a document indistinguishable from a real ticket for a purchase that did not
     * complete. {@code REFUNDED} is the sharp case: the money has already gone back, so a PDF that
     * still admits someone at a door is worse than none.
     *
     * <p>Returns the document rather than the bytes because <strong>rendering must not happen inside
     * this transaction</strong> (ADR-023). PDFBox builds a document in memory — CPU work, not SQL —
     * and holding a pooled connection across it puts a page render in front of the pool that
     * checkout is competing for.
     */
    @Transactional(readOnly = true)
    public TicketDocument ticketDocumentFor(
            String orderNumber, String sessionId, String receiptToken) {

        Order order = orders.findByOrderNumber(orderNumber)
                .orElseThrow(() -> new OrderNotFoundException(orderNumber));

        boolean ownSession = order.getUserSessionId().equals(sessionId);
        boolean validToken = receiptToken != null && receiptTokens.authorises(receiptToken, orderNumber);
        if (!ownSession && !validToken) {
            throw new OrderNotFoundException(orderNumber);
        }
        if (order.getStatus() != OrderStatus.CONFIRMED) {
            throw new TicketNotAvailableException(orderNumber, order.getStatus());
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
