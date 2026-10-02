package com.flashseats.order.service;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.TierSummary;
import com.flashseats.hold.exception.HoldAlreadySettledException;
import com.flashseats.hold.exception.HoldExpiredException;
import com.flashseats.hold.exception.HoldNotFoundException;
import com.flashseats.hold.facade.HoldFacade;
import com.flashseats.hold.facade.HoldSummary;
import com.flashseats.order.model.Order;
import com.flashseats.order.model.OrderStatus;
import com.flashseats.order.repository.OrderRepository;
import com.flashseats.payment.event.PaymentSettledEvent;
import com.flashseats.payment.facade.PaymentResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * Finishes a purchase whose buyer never saw the response: the charge settled, the HTTP answer was
 * lost, and the provider's webhook is the only witness.
 *
 * <p>A plain synchronous {@link EventListener}, because the Modulith publication registry was removed
 * (ADR-009) and a failure here must become the webhook's non-2xx. It re-claims the hold exactly as
 * checkout does, and if the seats are gone it refunds rather than confirm inventory another buyer
 * holds (ADR-012). It prices nothing: the confirmation prices from the tier (ADR-013).
 *
 * <p>It races the checkout that made the charge: Stripe sends this webhook the moment the payment
 * succeeds, which for 3-D Secure is the moment the buyer's browser re-POSTs. Whichever path writes the
 * order row first decides how the charge ends, and the other one finds out from that row (ADR-064).
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

        if (order.getStatus() == OrderStatus.CONFIRMED
                || order.getStatus() == OrderStatus.REFUNDED
                || order.getStatus() == OrderStatus.REFUND_FAILED) {
            // The synchronous path already resolved this, or a previous delivery did, and settling
            // again would consume a hold that is already consumed. Usually this is that same charge
            // arriving late. If it is a different one, it is a second charge for these seats, and the
            // webhook is the one witness of every settled charge, so it gives it back (ADR-075).
            log.debug(
                    "Order {} is already {} — webhook settlement has nothing to settle",
                    order.getOrderNumber(),
                    order.getStatus());
            refunds.returnIfStray(
                    order.getOrderNumber(), order.getGatewayReference(), chargeOf(event), order.getTotalAmountCents());
            return;
        }

        settle(order, event);
    }

    /**
     * <strong>Only a definite failure is compensated</strong> (ADR-056). The exceptions caught here
     * say this order did not take its seats just now. Anything else, such as a pool timeout or a
     * failed commit, is ambiguous and propagates, which releases the webhook claim and earns a
     * redelivery. ADR-046's rule, applied to money.
     *
     * <p>"Did not take its seats" is still not "the seats are gone". A hold this order consumed reads
     * as expired too, because the checkout confirmed the purchase between the read in
     * {@link #onPaymentSettled} and the claim here. The refund claim tells the two apart: it moves an
     * order only from {@code PENDING} or {@code FAILED}, so a confirmed purchase is left alone
     * (ADR-064).
     */
    private void settle(Order order, PaymentSettledEvent event) {
        String orderNumber = order.getOrderNumber();
        try {
            HoldSummary hold = holds.getActiveHold(event.holdToken(), order.getUserSessionId());
            TierSummary tier = catalog.getTierSummary(hold.eventId(), hold.tierId());

            commit.confirm(orderNumber, hold, tier, resultOf(event));
            log.info("Order {} confirmed from a webhook settlement", orderNumber);

        } catch (HoldNotFoundException
                | HoldExpiredException
                | HoldAlreadySettledException
                | OptimisticLockingFailureException claimLost) {
            OrderRefundService.Outcome outcome = refunds.refund(
                    orderNumber,
                    chargeOf(event),
                    order.getTotalAmountCents(),
                    "webhook settled against a reservation that no longer exists");
            if (outcome == OrderRefundService.Outcome.RESOLVED_ELSEWHERE) {
                log.info("Order {} was resolved by the checkout before its webhook; nothing to do", orderNumber);
            }
        }
    }

    private static SettledCharge chargeOf(PaymentSettledEvent event) {
        return new SettledCharge(event.transactionReference(), event.gatewayReference());
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
