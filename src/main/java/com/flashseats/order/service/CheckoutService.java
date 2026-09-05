package com.flashseats.order.service;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.catalog.facade.TierSummary;
import com.flashseats.hold.facade.HoldFacade;
import com.flashseats.hold.facade.HoldSummary;
import com.flashseats.order.config.OrderProperties;
import com.flashseats.order.dto.CheckoutRequest;
import com.flashseats.order.dto.OrderReceiptResponse;
import com.flashseats.order.exception.CheckoutWindowClosedException;
import com.flashseats.order.exception.InsufficientTimeRemainingException;
import com.flashseats.order.exception.OrderRefundedException;
import com.flashseats.payment.exception.DuplicatePaymentException;
import com.flashseats.payment.exception.PaymentDeclinedException;
import com.flashseats.payment.facade.AuthorizeCommand;
import com.flashseats.payment.facade.PaymentFacade;
import com.flashseats.payment.facade.PaymentResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The single checkout entry point, and the riskiest code in the system.
 *
 * <p>The sequence below is ADR-001 in order, and the order is the design:
 *
 * <ol>
 *   <li>validate the hold — nothing happens without live, owned seats
 *   <li>price it <strong>server-side</strong>; no client value reaches the amount
 *   <li>check the sale window
 *   <li>find-or-create the order row on {@code UNIQUE(hold_token)}
 *   <li>grant the single grace extension — <strong>and abort if it cannot be granted</strong>
 *   <li>charge, <strong>outside every transaction</strong>
 *   <li>in one transaction: consume the hold, confirm the order, write the items and the outbox row
 *   <li>after commit: best-effort cleanup that is safe to lose
 *   <li>if the commit failed after money moved: refund, and tell the buyer
 * </ol>
 *
 * <p><strong>Every exit past step 4 leaves the order resumable</strong> (ADR-034). The row is
 * committed as {@code PENDING} before the charge, so an exit that recorded no outcome — a gateway
 * outage, a hold settled underneath us, too little time left — would otherwise strand the buyer
 * holding seats they can no longer buy, being told by a {@code 409} that a charge they never made
 * is still running.
 *
 * <p><strong>This class is deliberately not {@code @Transactional}.</strong> It calls an external
 * provider; a transaction spanning that call would hold a pooled connection across a network round
 * trip, and under virtual threads the pool — not the thread count — is the system's real concurrency
 * limit, so one slow gateway would throttle checkout for everyone (ADR-023). The transactional work
 * lives in {@link OrderCommitService}.
 */
@Slf4j
@Service
public class CheckoutService {

    private final HoldFacade holds;
    private final CatalogFacade catalog;
    private final PaymentFacade payments;
    private final OrderCommitService commit;
    private final OrderQueryService queries;
    private final OrderProperties properties;
    private final Clock clock;

    public CheckoutService(
            HoldFacade holds,
            CatalogFacade catalog,
            PaymentFacade payments,
            OrderCommitService commit,
            OrderQueryService queries,
            OrderProperties properties,
            Clock clock) {
        this.holds = holds;
        this.catalog = catalog;
        this.payments = payments;
        this.commit = commit;
        this.queries = queries;
        this.properties = properties;
        this.clock = clock;
    }

    public CheckoutOutcome checkout(String sessionId, CheckoutRequest request) {

        // 0. Already bought? Return the receipt. This has to come first: a successful purchase
        //    consumes its hold, so checking the hold first would answer a resubmission with
        //    "your reservation expired" when the buyer in fact already owns the seats.
        Optional<OrderReceiptResponse> alreadyBought =
                queries.findConfirmedReceiptFor(request.holdToken());
        if (alreadyBought.isPresent()) {
            return new CheckoutOutcome(alreadyBought.get(), true);
        }

        // 1. The hold must be live and this session's. Throws 404/410 otherwise.
        HoldSummary hold = holds.getActiveHold(request.holdToken(), sessionId);

        // 2. Price from the tier, never from the request (ADR-013).
        TierSummary tier = catalog.getTierSummary(hold.eventId(), hold.tierId());
        long amountCents = tier.priceCents() * hold.quantity();

        // 3. Sale window, with the post-close grace for buyers already at the payment form.
        requireCheckoutWindow(tier);

        // 4. Find-or-create on UNIQUE(hold_token). A completed checkout replays as 200 + receipt.
        CheckoutOrder order = commit.findOrCreate(
                request.holdToken(), sessionId, request.userEmail(), hold, amountCents, tier.currency());
        if (order.alreadyConfirmed()) {
            return new CheckoutOutcome(queries.receiptFor(order.orderNumber()), true);
        }

        // Everything from here can fail, and the order row is already committed as PENDING. A
        // PENDING order that nothing ever resolves is a dead end — the buyer holds live seats they
        // can no longer buy — so every exit below leaves the row in a state a retry can resume
        // (ADR-034).
        try {
            // 5. The one grace extension. Idempotent across retries; throws if the hold has been
            //    settled by a concurrent expiry — in which case we must NOT charge (ADR-023).
            Instant expiresAt = holds.grantGrace(request.holdToken());
            requireTimeToComplete(expiresAt);

            // 6. Money moves here, with no transaction open.
            PaymentResult payment = payments.authorize(new AuthorizeCommand(
                    order.orderNumber(),
                    request.holdToken(),
                    sessionId,
                    amountCents,
                    tier.currency(),
                    request.paymentMethodId(),
                    request.idempotencyKey(),
                    order.attemptNumber() + 1));

            if (!payment.succeeded()) {
                // The hold stays ACTIVE. The buyer was promised they could try another card.
                int attemptsRemaining =
                        commit.recordFailedAttempt(order.orderNumber(), payment.failureReason());
                throw new PaymentDeclinedException(payment.failureReason(), attemptsRemaining, expiresAt);
            }

            // 7. One transaction: consume, confirm, items, outbox. 8. Post-commit cleanup hangs off it.
            try {
                commit.confirm(order.orderNumber(), hold, tier, payment);
            } catch (RuntimeException commitFailed) {
                // 9. Money moved but the seats did not. Give it back and say so.
                compensate(order.orderNumber(), payment, amountCents, commitFailed);
                throw new OrderRefundedException(order.orderNumber());
            }
        } catch (DuplicatePaymentException concurrent) {
            // Another request owns this order right now. Leave its state entirely alone — deciding
            // the outcome of someone else's in-flight charge is exactly the race the guard exists
            // to prevent.
            throw concurrent;
        } catch (RuntimeException unresolved) {
            // No charge outcome was reached, or the outcome was already recorded. markAbandoned only
            // touches a row still PENDING, so a decline (already FAILED) and a compensated commit
            // failure (already REFUNDED) both pass through it untouched — the guard is what makes
            // this catch safe to wrap everything.
            commit.markAbandoned(order.orderNumber(), unresolved.getMessage());
            throw unresolved;
        }

        return new CheckoutOutcome(queries.receiptFor(order.orderNumber()), false);
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Allows {@code OPEN}, and {@code CLOSED} within the grace window (ADR-016). A buyer who reached
     * the payment form seconds before the sale ended should be able to finish.
     */
    private void requireCheckoutWindow(TierSummary tier) {
        if (tier.windowStatus() == EventWindowStatus.OPEN) {
            return;
        }
        Instant graceEnds = tier.saleEndTime().plus(Duration.ofMinutes(properties.getCheckoutGraceMinutes()));
        if (tier.windowStatus() == EventWindowStatus.CLOSED && clock.instant().isBefore(graceEnds)) {
            return;
        }
        throw new CheckoutWindowClosedException();
    }

    /**
     * Refuses to start a charge that cannot finish inside the reservation (ADR-030).
     *
     * <p>Telling the buyer plainly that there is not enough time is better than charging them and
     * then discovering the seats are gone — that path exists, but it ends in a refund and a
     * confusing bank statement.
     */
    private void requireTimeToComplete(Instant expiresAt) {
        long secondsLeft = Duration.between(clock.instant(), expiresAt).toSeconds();
        if (secondsLeft < properties.getMinRemainingSecondsForRetry()) {
            throw new InsufficientTimeRemainingException(expiresAt);
        }
    }

    private void compensate(
            String orderNumber, PaymentResult payment, long amountCents, RuntimeException cause) {
        log.error(
                "Order {} could not be committed after a settled charge — refunding {} cents",
                orderNumber,
                amountCents,
                cause);
        payments.refund(payment.transactionReference(), amountCents, "order commit failed");
        commit.markRefunded(orderNumber, cause.getMessage());
    }
}
