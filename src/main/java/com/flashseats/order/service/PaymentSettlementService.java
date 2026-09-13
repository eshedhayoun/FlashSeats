package com.flashseats.order.service;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.TierSummary;
import com.flashseats.hold.facade.HoldFacade;
import com.flashseats.hold.facade.HoldSummary;
import com.flashseats.order.model.Order;
import com.flashseats.order.model.OrderStatus;
import com.flashseats.order.repository.OrderRepository;
import com.flashseats.payment.event.PaymentSettledEvent;
import com.flashseats.payment.facade.PaymentResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * Finishes a purchase whose buyer never saw the response.
 *
 * <p>The charge settled at the provider and the HTTP answer was lost — a dropped connection, a
 * killed replica, a closed laptop. The money moved and, until this runs, nothing in this system
 * knows it. The provider's webhook is the only remaining witness.
 *
 * <p>A plain {@link EventListener}, not {@code @ApplicationModuleListener}: the latter needs the
 * Modulith event-publication registry, which was deliberately removed in favour of the hand-rolled
 * outbox (ADR-009). Synchronous on purpose — the webhook receiver's contract is that a failure here
 * becomes a non-2xx, and a listener that failed asynchronously could not be reported to the provider
 * at all.
 *
 * <p><strong>It may not finalise an order whose seats are gone</strong> (ADR-012). The hold can
 * easily have expired during exactly the disconnect that made this webhook necessary, and by now
 * another buyer may own those seats. Confirming anyway would charge one customer for inventory
 * another already holds. So the hold is re-claimed the same way the synchronous path claims it, and
 * if that fails the charge is refunded and the buyer is told.
 *
 * <p>Note what this does <em>not</em> do: price anything. {@code amountCents} arrives on the event
 * and is used only to size the refund; the confirmation prices from the tier like every other path
 * (ADR-013).
 */
@Slf4j
@Service
public class PaymentSettlementService {

    private final OrderRepository orders;
    private final HoldFacade holds;
    private final CatalogFacade catalog;
    private final OrderCommitService commit;
    private final OrderRefundService refunds;

    public PaymentSettlementService(
            OrderRepository orders,
            HoldFacade holds,
            CatalogFacade catalog,
            OrderCommitService commit,
            OrderRefundService refunds) {
        this.orders = orders;
        this.holds = holds;
        this.catalog = catalog;
        this.commit = commit;
        this.refunds = refunds;
    }

    /**
     * Deliberately not {@code @Transactional}. Everything below either is a repository call, which
     * carries its own short transaction, or is {@link OrderCommitService}'s — and the refund arm
     * makes a network call to the provider, which may never sit inside one (ADR-023).
     */
    @EventListener
    public void onPaymentSettled(PaymentSettledEvent event) {
        Order order = orders.findByHoldToken(event.holdToken()).orElse(null);

        if (order == null) {
            // A charge with no order is not something this path can repair, and asking for a
            // redelivery would reach the same conclusion for ever. It is, however, money taken
            // against nothing, so it is logged loudly rather than dropped.
            log.error(
                    "Settled charge {} names hold {}, which has no order — manual reconciliation required",
                    event.gatewayReference(),
                    event.holdToken());
            return;
        }

        if (order.getStatus() == OrderStatus.CONFIRMED || order.getStatus() == OrderStatus.REFUNDED) {
            // The synchronous path already resolved this, or a previous delivery did. Nothing to do,
            // and doing it again would consume a hold that is already consumed.
            log.debug(
                    "Order {} is already {} — webhook settlement has nothing to do",
                    order.getOrderNumber(),
                    order.getStatus());
            return;
        }

        settle(order, event);
    }

    private void settle(Order order, PaymentSettledEvent event) {
        String orderNumber = order.getOrderNumber();
        try {
            HoldSummary hold = holds.getActiveHold(event.holdToken(), order.getUserSessionId());
            TierSummary tier = catalog.getTierSummary(hold.eventId(), hold.tierId());

            commit.confirm(orderNumber, hold, tier, resultOf(event));
            log.info("Order {} confirmed from a webhook settlement", orderNumber);

        } catch (RuntimeException seatsGone) {
            log.warn(
                    "Webhook settlement for order {} could not claim hold {} — refunding",
                    orderNumber,
                    event.holdToken(),
                    seatsGone);
            refunds.refund(
                    orderNumber,
                    event.transactionReference(),
                    order.getTotalAmountCents(),
                    "webhook settled against a reservation that no longer exists");
        }
    }

    /**
     * The event, shaped as {@link OrderCommitService#confirm} expects.
     *
     * <p>{@code transactionReference} may be {@code null} when no ledger row matches the provider's
     * intent, which {@code orders.payment_transaction_ref} then records as unknown — honest, and
     * visibly different from a charge this system made itself.
     */
    private static PaymentResult resultOf(PaymentSettledEvent event) {
        return new PaymentResult(
                event.transactionReference(),
                true,
                event.gatewayReference(),
                null,
                null,
                null,
                false,
                false);
    }
}
