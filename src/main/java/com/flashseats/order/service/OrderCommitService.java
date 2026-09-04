package com.flashseats.order.service;

import tools.jackson.databind.ObjectMapper;
import com.flashseats.catalog.facade.TierSummary;
import com.flashseats.hold.facade.HoldFacade;
import com.flashseats.hold.facade.HoldSummary;
import com.flashseats.order.config.OrderProperties;
import com.flashseats.order.event.OrderConfirmedEvent;
import com.flashseats.order.exception.OrderRefundedException;
import com.flashseats.order.model.Order;
import com.flashseats.order.model.OrderItem;
import com.flashseats.order.model.OrderStatus;
import com.flashseats.order.model.OutboxEvent;
import com.flashseats.order.repository.OrderItemRepository;
import com.flashseats.order.repository.OrderRepository;
import com.flashseats.order.repository.OutboxEventRepository;
import com.flashseats.payment.exception.DuplicatePaymentException;
import com.flashseats.payment.exception.PaymentDeclinedException;
import com.flashseats.payment.facade.PaymentResult;
import java.time.Clock;
import java.util.List;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of checkout.
 *
 * <p>Separate from {@link CheckoutService} because Spring's transaction proxy does not intercept
 * self-invocation: a {@code @Transactional} method called from another method on the same object
 * runs with <strong>no transaction at all</strong>, silently. Splitting the class is what makes the
 * boundary real — and it puts the boundary somewhere a reader can see it.
 *
 * <p>Every method here contains SQL and nothing else. No HTTP, no Redis, no broker, no rendering
 * (ADR-023).
 */
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
     * Find-or-create on {@code UNIQUE(hold_token)} (ADR-002).
     *
     * <p>The existing row's state decides what happens, and each case is a deliberate choice:
     *
     * <table border="1">
     *   <caption>Find-or-create behaviour</caption>
     *   <tr><th>Existing row</th><th>Behaviour</th></tr>
     *   <tr><td>none</td><td>insert {@code PENDING} and proceed</td></tr>
     *   <tr><td>{@code PENDING}</td><td>{@code 409} — a charge is already in flight</td></tr>
     *   <tr><td>{@code FAILED}</td><td>reset to {@code PENDING} and retry on the <em>same</em> order number</td></tr>
     *   <tr><td>{@code CONFIRMED}</td><td>{@code 200} — replay the existing receipt</td></tr>
     *   <tr><td>{@code REFUNDED}</td><td>terminal</td></tr>
     * </table>
     *
     * <p>Reusing the order number across retries matters to the buyer: three declined attempts should
     * not produce three references to explain to support.
     */
    @Transactional
    public CheckoutOrder findOrCreate(
            String holdToken, String sessionId, String email, HoldSummary hold, long amountCents,
            String currency) {

        Order existing = orders.findByHoldToken(holdToken).orElse(null);
        if (existing != null) {
            return switch (existing.getStatus()) {
                case CONFIRMED -> new CheckoutOrder(existing.getOrderNumber(), existing.getPaymentAttempts(), true);
                case PENDING -> throw new DuplicatePaymentException(holdToken);
                case REFUNDED -> throw new OrderRefundedException(existing.getOrderNumber());
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
            throw new DuplicatePaymentException(holdToken);
        }
        return new CheckoutOrder(orderNumber, 0, false);
    }

    /**
     * <strong>The one transaction.</strong> Consumes the hold, writes the ledger, and enqueues
     * fulfilment — all or nothing.
     *
     * <p>{@code consumeHold} is a conditional {@code UPDATE} that joins this transaction, so if
     * anything below it fails, the hold returns to {@code ACTIVE} and expires normally. That is the
     * whole reason the claim lives in SQL rather than in Redis, which cannot roll back (ADR-019).
     *
     * <p>The outbox row is written here, not after: an order that is confirmed but whose ticket was
     * never queued is not a state this system can reach.
     */
    @Transactional
    public Order confirm(
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

        return order;
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

    private CheckoutOrder resumeFailed(Order order) {
        if (order.getPaymentAttempts() >= properties.getMaxPaymentAttempts()) {
            throw new PaymentDeclinedException(
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
