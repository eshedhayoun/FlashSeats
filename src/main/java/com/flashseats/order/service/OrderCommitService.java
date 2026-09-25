package com.flashseats.order.service;

import com.flashseats.catalog.facade.TierSummary;
import com.flashseats.hold.facade.HoldFacade;
import com.flashseats.hold.facade.HoldSummary;
import com.flashseats.order.config.OrderProperties;
import com.flashseats.order.dto.OrderItemResponse;
import com.flashseats.order.dto.OrderReceiptResponse;
import com.flashseats.order.exception.OrderErrors;
import com.flashseats.order.model.Order;
import com.flashseats.order.model.OrderItem;
import com.flashseats.order.model.OrderStatus;
import com.flashseats.order.model.OutboxEvent;
import com.flashseats.order.repository.OrderItemRepository;
import com.flashseats.order.repository.OrderRepository;
import com.flashseats.order.repository.OutboxEventRepository;
import com.flashseats.payment.exception.DuplicatePaymentException;
import com.flashseats.payment.exception.PaymentErrors;
import com.flashseats.payment.facade.PaymentResult;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/**
 * The transactional half of checkout. A separate bean from {@link CheckoutService} because Spring's
 * proxy does not intercept self-invocation. Every method here is SQL and nothing else (ADR-023).
 */
@Slf4j
@Component
public class OrderCommitService {

    private static final String AGGREGATE_TYPE = "ORDER";
    static final String EVENT_ORDER_CONFIRMED = "ORDER_CONFIRMED";
    static final String EVENT_ORDER_REFUNDED = "ORDER_REFUNDED";

    private final OrderRepository orders;
    private final OrderItemRepository items;
    private final OutboxEventRepository outbox;
    private final HoldFacade holds;
    private final OrderNumbers orderNumbers;
    private final ReceiptTokens receiptTokens;
    private final OrderProperties properties;
    private final ApplicationEventPublisher events;
    private final ObjectMapper json;
    private final Clock clock;

    public OrderCommitService(
            OrderRepository orders,
            OrderItemRepository items,
            OutboxEventRepository outbox,
            HoldFacade holds,
            OrderNumbers orderNumbers,
            ReceiptTokens receiptTokens,
            OrderProperties properties,
            ApplicationEventPublisher events,
            ObjectMapper json,
            Clock clock) {
        this.orders = orders;
        this.items = items;
        this.outbox = outbox;
        this.holds = holds;
        this.orderNumbers = orderNumbers;
        this.receiptTokens = receiptTokens;
        this.properties = properties;
        this.events = events;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Find-or-create on {@code UNIQUE(hold_token)} (ADR-002). The existing row decides:
     *
     * <table border="1">
     *   <caption>Find-or-create behaviour</caption>
     *   <tr><th>Existing row</th><th>Behaviour</th></tr>
     *   <tr><td>none</td><td>insert {@code PENDING} and proceed</td></tr>
     *   <tr><td>{@code PENDING}, fresh</td><td>{@code 409}: a charge is already in flight</td></tr>
     *   <tr><td>{@code PENDING}, stale</td><td>resume on the same order number (ADR-034)</td></tr>
     *   <tr><td>{@code FAILED}</td><td>reset to {@code PENDING}, retry on the same order number</td></tr>
     *   <tr><td>{@code CONFIRMED}</td><td>{@code 200}: replay the receipt</td></tr>
     *   <tr><td>{@code REFUNDED}</td><td>terminal</td></tr>
     * </table>
     *
     * <p>{@code PENDING} is in flight, never terminal (ADR-034). {@link CheckoutService} marks any thrown
     * exit {@code FAILED}; the staleness check covers a process killed between commit and charge. The
     * same order number across retries means three declines are one reference, not three.
     */
    @Transactional
    public CheckoutOrder findOrCreate(
            String holdToken, String sessionId, String email, HoldSummary hold, long amountCents,
            String currency) {

        Order existing = orders.findByHoldToken(holdToken).orElse(null);
        if (existing != null) {
            return switch (existing.getStatus()) {
                case CONFIRMED -> new CheckoutOrder(existing.getOrderNumber(), existing.getPaymentAttempts(), true);
                case PENDING -> resumeIfStranded(existing, holdToken);
                case REFUNDED -> throw OrderErrors.refunded();
                case FAILED -> resumeFailed(existing);
            };
        }

        String orderNumber = orderNumbers.next();
        Order order = new Order(
                orderNumber,
                holdToken,
                sessionId,
                email,
                receiptTokens.issue(orderNumber),
                hold.eventId(),
                amountCents,
                currency);
        try {
            orders.saveAndFlush(order);
        } catch (DataIntegrityViolationException concurrentCheckout) {
            // Two requests raced to create the row. UNIQUE(hold_token) let exactly one win; this is
            // the other one, and it is the same situation as finding a PENDING row above.
            throw new DuplicatePaymentException();
        }
        return new CheckoutOrder(orderNumber, 0, false);
    }

    /**
     * <strong>The one transaction</strong>: consume the hold, write the ledger, enqueue fulfilment,
     * all or nothing. {@code consumeHold} is a conditional {@code UPDATE} that joins this transaction,
     * so a failure below returns the hold to {@code ACTIVE}; that is why the claim lives in SQL rather
     * than Redis (ADR-019). Returns the receipt directly, so a successful checkout needs no second
     * pooled read.
     */
    @Transactional
    public OrderReceiptResponse confirm(
            String orderNumber, HoldSummary hold, TierSummary tier, PaymentResult payment) {

        // Claim the seats first. Throws HoldAlreadySettledException if a concurrent expiry won,
        // which rolls this transaction back and sends the caller down the refund path.
        //
        // Loading the order AFTER the claim, not before, is deliberate: the claim is a bulk update,
        // and a bulk update that ever cleared the persistence context would detach an order loaded
        // ahead of it and quietly drop the changes below.
        holds.consumeHold(hold.holdToken());

        Order order = orders.findByOrderNumber(orderNumber).orElseThrow();
        order.setStatus(OrderStatus.CONFIRMED);
        order.setPaymentTransactionRef(payment.transactionReference());
        order.setGatewayReference(payment.gatewayReference());
        order.setPaymentAttempts(order.getPaymentAttempts() + 1);
        order.setFailureReason(null);

        OrderItem item = new OrderItem(
                order.getId(),
                hold.eventId(),
                hold.tierId(),
                tier.tierName(),
                hold.quantity(),
                tier.priceCents());
        items.save(item);

        outbox.save(new OutboxEvent(
                AGGREGATE_TYPE,
                orderNumber,
                EVENT_ORDER_CONFIRMED,
                serialise(confirmedPayload(order, tier, hold))));

        events.publishEvent(new OrderConfirmedEvent(
                orderNumber, hold.holdToken(), order.getUserSessionId(), hold.eventId(), clock.instant()));

        // Built from the row and the line item written moments ago, not re-read. The line item is
        // the one just constructed, so this costs no query either.
        return new OrderReceiptResponse(
                order.getOrderNumber(),
                order.getStatus(),
                order.getUserEmail(),
                order.getTotalAmountCents(),
                order.getCurrency(),
                order.getReceiptToken(),
                order.getCreatedAt(),
                List.of(new OrderItemResponse(
                        item.getEventId(),
                        item.getTierId(),
                        item.getTierName(),
                        item.getQuantity(),
                        item.getUnitPriceCents())));
    }

    /**
     * Records a declined attempt.
     *
     * <p>The order becomes {@code FAILED} but <strong>the hold is deliberately left {@code ACTIVE}</strong>
     * — the buyer was told they could try another card, and taking their seats away would contradict
     * that. Nor is a new grace extension granted: the budget is per hold (ADR-030).
     *
     * @return how many attempts the buyer has left
     */
    @Transactional
    public int recordFailedAttempt(String orderNumber, String failureReason) {
        Order order = orders.findByOrderNumber(orderNumber).orElseThrow();
        order.setStatus(OrderStatus.FAILED);
        order.setPaymentAttempts(order.getPaymentAttempts() + 1);
        order.setFailureReason(failureReason);
        return Math.max(0, properties.getMaxPaymentAttempts() - order.getPaymentAttempts());
    }

    /**
     * Ends a checkout that never reached a charge outcome (ADR-034). The order becomes {@code FAILED},
     * which {@link #findOrCreate} resumes on the same order number, and <strong>no payment attempt is
     * consumed</strong>. The hold is left {@code ACTIVE}.
     */
    @Transactional
    public void markAbandoned(String orderNumber, String reason) {
        orders.findByOrderNumber(orderNumber).ifPresent(order -> {
            if (order.getStatus() == OrderStatus.PENDING) {
                order.setStatus(OrderStatus.FAILED);
                order.setFailureReason(reason);
            }
        });
    }

    /**
     * Records that a settled charge was refunded because the seats could not be delivered, and
     * queues a notice so the buyer hears it from us rather than from their bank statement (ADR-012).
     */
    @Transactional
    public void markRefunded(String orderNumber, String reason) {
        Order order = orders.findByOrderNumber(orderNumber).orElseThrow();
        order.setStatus(OrderStatus.REFUNDED);
        order.setFailureReason(reason);

        outbox.save(new OutboxEvent(
                AGGREGATE_TYPE,
                orderNumber,
                EVENT_ORDER_REFUNDED,
                serialise(new OutboxPayload(
                        EVENT_ORDER_REFUNDED,
                        orderNumber,
                        order.getReceiptToken(),
                        order.getUserEmail(),
                        order.getTotalAmountCents(),
                        order.getCurrency(),
                        clock.instant(),
                        null,
                        List.of()))));
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Decides whether a {@code PENDING} row is a live charge or a stranded one (ADR-034). Within
     * {@code stalePendingSeconds} a charge may still be running, so a second request gets {@code 409};
     * beyond it, the row is a crash artefact and the retry resumes it.
     */
    private CheckoutOrder resumeIfStranded(Order order, String holdToken) {
        Instant strandedBefore =
                clock.instant().minusSeconds(properties.getStalePendingSeconds());
        if (order.getUpdatedAt().isAfter(strandedBefore)) {
            throw new DuplicatePaymentException();
        }
        log.warn(
                "Resuming order {} stranded in PENDING since {}", order.getOrderNumber(), order.getUpdatedAt());
        return resumeFailed(order);
    }

    private CheckoutOrder resumeFailed(Order order) {
        if (order.getPaymentAttempts() >= properties.getMaxPaymentAttempts()) {
            throw PaymentErrors.declined(
                    "No payment attempts remain for this reservation.", 0, null);
        }
        order.setStatus(OrderStatus.PENDING);
        return new CheckoutOrder(order.getOrderNumber(), order.getPaymentAttempts(), false);
    }

    private OutboxPayload confirmedPayload(Order order, TierSummary tier, HoldSummary hold) {
        return new OutboxPayload(
                EVENT_ORDER_CONFIRMED,
                order.getOrderNumber(),
                order.getReceiptToken(),
                order.getUserEmail(),
                order.getTotalAmountCents(),
                order.getCurrency(),
                clock.instant(),
                new OutboxPayload.EventInfo(
                        tier.eventId(), tier.eventTitle(), tier.venueName(), tier.eventStartTime()),
                List.of(new OutboxPayload.Item(
                        hold.tierId(), tier.tierName(), hold.quantity(), tier.priceCents())));
    }

    /**
     * Jackson 3 throws unchecked, so there is nothing to catch here — and nothing that should be.
     * If the fulfilment message cannot be written, the order must not commit: a confirmed purchase
     * with no way to deliver the ticket is worse than a failed one.
     */
    private String serialise(OutboxPayload payload) {
        return json.writeValueAsString(payload);
    }
}
