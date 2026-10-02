package com.flashseats.order.service;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.catalog.facade.TierSummary;
import com.flashseats.hold.exception.HoldAlreadySettledException;
import com.flashseats.hold.exception.HoldExpiredException;
import com.flashseats.hold.exception.HoldNotFoundException;
import com.flashseats.hold.facade.HoldFacade;
import com.flashseats.hold.facade.HoldSummary;
import com.flashseats.order.config.OrderProperties;
import com.flashseats.order.dto.CheckoutRequest;
import com.flashseats.order.exception.OrderErrors;
import com.flashseats.payment.exception.DuplicatePaymentException;
import com.flashseats.payment.exception.PaymentErrors;
import com.flashseats.payment.facade.AuthorizeCommand;
import com.flashseats.payment.facade.PaymentFacade;
import com.flashseats.payment.facade.PaymentResult;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * The single checkout entry point. The sequence is ADR-001 in order:
 *
 * <ol>
 *   <li>validate the hold
 *   <li>price it <strong>server-side</strong>
 *   <li>check the sale window
 *   <li>find-or-create the order on {@code UNIQUE(hold_token)}
 *   <li>grant the single grace extension, and abort if it cannot be granted
 *   <li>charge, <strong>outside every transaction</strong>
 *   <li>in one transaction: consume the hold, confirm, write items and the outbox row
 *   <li>after commit: best-effort cleanup
 *   <li>if the order lost its hold after money moved: refund, and say so
 * </ol>
 *
 * <p>Every exit past step 4 leaves the order resumable (ADR-034). Not {@code @Transactional}: it
 * calls the provider, and a pooled connection held across that call throttles every checkout
 * (ADR-023). The transactional steps are in {@link OrderCommitService}.
 *
 * <p><strong>A hold that has gone is not proof the seats have gone</strong> (ADR-064). Only this
 * order can consume its hold, so a {@code CONSUMED} hold means this purchase succeeded — on the
 * webhook path, which settles the same charge. Every place a hold can turn out to be gone therefore
 * asks the order row before answering, and only a refund claim on that row moves money.
 */
@Slf4j
@Service
public class CheckoutService {

    private final HoldFacade holds;
    private final CatalogFacade catalog;
    private final PaymentFacade payments;
    private final OrderCommitService commit;
    private final OrderRefundService refunds;
    private final OrderQueryService queries;
    private final OrderProperties properties;
    private final Clock clock;

    public CheckoutService(
            HoldFacade holds,
            CatalogFacade catalog,
            PaymentFacade payments,
            OrderCommitService commit,
            OrderRefundService refunds,
            OrderQueryService queries,
            OrderProperties properties,
            Clock clock) {
        this.holds = holds;
        this.catalog = catalog;
        this.payments = payments;
        this.commit = commit;
        this.refunds = refunds;
        this.queries = queries;
        this.properties = properties;
        this.clock = clock;
    }

    public CheckoutOutcome checkout(String sessionId, CheckoutRequest request) {

        // 0. Already bought? Return the receipt. This has to come first: a successful purchase
        //    consumes its hold, so checking the hold first would answer a resubmission with
        //    "your reservation expired" when the buyer in fact already owns the seats.
        Optional<CheckoutOutcome> alreadyBought = replayIfConfirmed(request.holdToken());
        if (alreadyBought.isPresent()) {
            return alreadyBought.get();
        }

        // 1. The hold must be live and this session's. Throws 404/410 otherwise — unless it is gone
        //    because the webhook completed this purchase since step 0 looked.
        HoldSummary hold;
        try {
            hold = holds.getActiveHold(request.holdToken(), sessionId);
        } catch (HoldNotFoundException | HoldExpiredException holdGone) {
            return replayIfConfirmed(request.holdToken()).orElseThrow(() -> holdGone);
        }

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

            // 6. Money moves here, with no transaction open. A retry after an ambiguous failure gets
            //    the charge that already settled back, not a second one (ADR-064).
            PaymentResult payment = payments.authorize(new AuthorizeCommand(
                    order.orderNumber(),
                    request.holdToken(),
                    sessionId,
                    amountCents,
                    tier.currency(),
                    request.paymentMethodId(),
                    request.idempotencyKey(),
                    order.attemptNumber() + 1));

            // 3-D Secure. Thrown, so the catch-all below marks the order FAILED — resumable on the
            // same order number, with NO attempt consumed (ADR-034). The buyer completes the
            // challenge and re-POSTs this same body; there is no resume endpoint, and adding one
            // would be a second retry mechanism (FE_SPEC §2). The grace extension granted at step 5
            // is what pays for the challenge window (ADR-030).
            if (payment.requiresAction()) {
                throw PaymentErrors.actionRequired(payment.clientSecret(), expiresAt);
            }

            if (!payment.succeeded()) {
                // The hold stays ACTIVE. The buyer was promised they could try another card.
                int attemptsRemaining =
                        commit.recordFailedAttempt(order.orderNumber(), payment.failureReason());
                throw PaymentErrors.declined(payment.failureReason(), attemptsRemaining, expiresAt);
            }

            // 7–9. Confirm, or settle the charge the other way if this order lost its hold.
            return confirmOrRefund(order.orderNumber(), request.holdToken(), hold, tier, payment, amountCents);

        } catch (DuplicatePaymentException concurrent) {
            // Another request owns this order right now. Leave its state entirely alone — deciding
            // the outcome of someone else's in-flight charge is exactly the race the guard exists
            // to prevent.
            throw concurrent;
        } catch (HoldNotFoundException | HoldExpiredException holdGone) {
            // Step 5 found the hold settled before any money moved. Nothing to refund; but if it
            // settled because the webhook completed this purchase, the buyer owns the seats.
            commit.markAbandoned(order.orderNumber(), holdGone.getMessage());
            return replayIfConfirmed(request.holdToken()).orElseThrow(() -> holdGone);
        } catch (RuntimeException unresolved) {
            // No charge outcome was reached, the outcome was already recorded, or the commit failed
            // in a way that proves nothing: a pool timeout, a dropped connection. None of those is a
            // reason to move money (ADR-056). markAbandoned only touches a row still PENDING, so a
            // decline, a refund and a confirmation that did land all pass through it untouched, and
            // the retry finds the order where it is — including a charge that settled, which it
            // reuses rather than repeats.
            commit.markAbandoned(order.orderNumber(), unresolved.getMessage());
            throw unresolved;
        }
    }

    /**
     * Steps 7–9. One transaction consumes the hold, confirms, and queues fulfilment; post-commit
     * cleanup hangs off it. The receipt comes back from the commit itself — everything in it was just
     * written, so re-reading the order would cost a second pooled connection on the one path where
     * the pool is the measured ceiling.
     *
     * <p>If the commit finds the hold already settled, or the order already resolved, this order did
     * not take its seats. The webhook may have confirmed this same charge a moment earlier — then the
     * refund claim fails, because it cannot move a {@code CONFIRMED} order, and the buyer gets the
     * receipt. Otherwise the reservation ended under the charge and the money goes back (ADR-064).
     */
    private CheckoutOutcome confirmOrRefund(
            String orderNumber,
            String holdToken,
            HoldSummary hold,
            TierSummary tier,
            PaymentResult payment,
            long amountCents) {
        try {
            return new CheckoutOutcome(commit.confirm(orderNumber, hold, tier, payment), false);
        } catch (HoldNotFoundException | HoldAlreadySettledException | OptimisticLockingFailureException lost) {
            log.warn("Order {} lost its hold after the charge settled; resolving from the order row", orderNumber, lost);
            if (refunds.refund(
                    orderNumber,
                    payment.transactionReference(),
                    amountCents,
                    "the reservation ended before the order could be confirmed")) {
                throw OrderErrors.refunded();
            }
            // Resolved by the other path: confirmed, or already refunded by it.
            return replayIfConfirmed(holdToken).orElseThrow(OrderErrors::refunded);
        }
    }

    /** The receipt, if this hold's order is confirmed: the buyer owns the seats, whoever confirmed them. */
    private Optional<CheckoutOutcome> replayIfConfirmed(String holdToken) {
        return queries.findConfirmedReceiptFor(holdToken).map(receipt -> new CheckoutOutcome(receipt, true));
    }

    // ----------------------------------------------------------------- helpers

    /**
     * Allows {@code OPEN}, {@code PAUSED}, and {@code CLOSED} within the grace window (ADR-016). A
     * buyer who reached the payment form seconds before the sale ended should be able to finish, and
     * so should one who holds seats when an operator pauses it: their seats are already out of the
     * counter, so paying moves no stock, while refusing would let the reservation run out under a
     * buyer who did nothing wrong (ADR-066).
     */
    private void requireCheckoutWindow(TierSummary tier) {
        if (tier.windowStatus() == EventWindowStatus.OPEN || tier.windowStatus() == EventWindowStatus.PAUSED) {
            return;
        }
        Instant graceEnds = tier.saleEndTime().plus(Duration.ofMinutes(properties.getCheckoutGraceMinutes()));
        if (tier.windowStatus() == EventWindowStatus.CLOSED && clock.instant().isBefore(graceEnds)) {
            return;
        }
        throw OrderErrors.checkoutWindowClosed();
    }

    /**
     * Refuses to start a charge that cannot finish inside the reservation (ADR-030).
     *
     * <p>Telling the buyer plainly that there is not enough time is better than charging them and
     * then discovering the seats are gone — that path exists, but it ends in a refund and a
     * confusing bank statement.
     */
    private void requireTimeToComplete(Instant expiresAt) {
        long secondsLeft = Duration.between(clock.instant(), expiresAt).getSeconds();
        if (secondsLeft < properties.getMinRemainingSecondsForRetry()) {
            throw OrderErrors.insufficientTimeRemaining(expiresAt);
        }
    }
}
