package com.flashseats.order.service;

import com.flashseats.payment.facade.PaymentFacade;
import com.flashseats.payment.facade.RefundResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Gives money back when the seats could not be delivered (ADR-012). Reached from
 * {@link CheckoutService} (the order lost its hold after the charge) and
 * {@link PaymentSettlementService} (a webhook for a hold already gone): one compensation, two
 * triggers.
 *
 * <p><strong>Claim first, money second</strong> (ADR-064). Both triggers can fire for the same charge
 * at the same moment, and one of them may be about to confirm it. The claim is a compare-and-set on
 * the order row that confirming also needs, so exactly one ending wins: refunding first and recording
 * second let a confirmation land in between, and the buyer kept the seats <em>and</em> the money.
 *
 * <p>Not {@code @Transactional}: it calls the provider (ADR-023). A refused refund leaves the order
 * {@code REFUND_FAILED}, sends no notice and is counted, so money we still hold surfaces for a human
 * rather than being described to the buyer as returned (ADR-069).
 */
@Slf4j
@Service
public class OrderRefundService {

    /** Non-zero means money is owed to a buyer that automation could not return. Alarm on any. */
    public static final String FAILED_REFUNDS = "flashseats.payment.refund.failed";

    /**
     * A second settled charge for one reservation, by outcome (ADR-075). Any count means two
     * checkouts for one hold both reached the provider; {@code returned} is the system recovering,
     * the other two are money a person has to look at.
     */
    public static final String STRAY_CHARGES = "flashseats.payment.charge.stray";

    /** How one call to {@link #refund} ended, which is what the caller tells the buyer. */
    public enum Outcome {
        /** The other path settling this charge got there first: confirmed it, or refunded it. */
        RESOLVED_ELSEWHERE,
        /** This call claimed the refund and the money went back. */
        REFUNDED,
        /** This call claimed the refund and the provider refused it: money owed, now with a person. */
        REFUND_FAILED
    }

    private final PaymentFacade payments;
    private final OrderCommitService commit;
    private final Counter failedRefunds;
    private final Counter straysReturned;
    private final Counter straysRefused;
    private final Counter straysUnaccounted;

    public OrderRefundService(PaymentFacade payments, OrderCommitService commit, MeterRegistry meters) {
        this.payments = payments;
        this.commit = commit;
        this.failedRefunds = Counter.builder(FAILED_REFUNDS)
                .description("Refunds the provider refused. Each one is money owed to a named buyer.")
                .register(meters);
        this.straysReturned = strayCounter(meters, "returned");
        this.straysRefused = strayCounter(meters, "refund_failed");
        this.straysUnaccounted = strayCounter(meters, "unaccounted");
    }

    /**
     * @param charge the settled charge; its {@code transactionReference} is {@code null} if the ledger
     *     row could not be found, which can only happen if the charge was made by something other than
     *     this system, and is recorded rather than swallowed
     * @return how it ended; {@link Outcome#RESOLVED_ELSEWHERE} means the order's own charge was not
     *     moved here
     */
    public Outcome refund(String orderNumber, SettledCharge charge, long amountCents, String reason) {

        if (!commit.claimRefund(orderNumber, charge, reason)) {
            log.info("Order {} was already resolved by the other settlement path", orderNumber);
            returnIfStray(orderNumber, commit.chargeOfRecord(orderNumber).orElse(null), charge, amountCents);
            return Outcome.RESOLVED_ELSEWHERE;
        }

        String transactionReference = charge.transactionReference();
        if (transactionReference == null) {
            failedRefunds.increment();
            log.error(
                    "REFUND IMPOSSIBLE for order {} ({} cents): no payment ledger row to refund against"
                            + " — manual reconciliation required",
                    orderNumber,
                    amountCents);
            commit.recordRefundFailure(orderNumber, "refund not issued: no payment transaction found");
            return Outcome.REFUND_FAILED;
        }

        log.warn(
                "Order {} could not be completed after a settled charge — refunding {} cents ({})",
                orderNumber,
                amountCents,
                reason);

        RefundResult result = payments.refund(transactionReference, amountCents, reason);

        if (result.succeeded()) {
            commit.recordRefunded(orderNumber, reason);
            return Outcome.REFUNDED;
        }

        failedRefunds.increment();
        log.error(
                "REFUND FAILED for order {} against {} ({} cents): {} — manual reconciliation required",
                orderNumber,
                transactionReference,
                amountCents,
                result.failureReason());
        commit.recordRefundFailure(orderNumber, "refund failed: " + result.failureReason());
        return Outcome.REFUND_FAILED;
    }

    /**
     * Gives back a settled charge that the order does not name (invariant 13, ADR-075).
     *
     * <p>An order names the one charge it ended with. Normally that is the only charge its hold ever
     * had: the in-flight guard and the {@code PENDING} check keep a second checkout away while the
     * first is charging. Both expire, though, so a checkout stalled past them can find a retry has
     * charged too. The order keeps one of the two; this returns the other, rather than leave a buyer
     * billed twice for one set of seats.
     *
     * <p>Moves money only on a <em>definite</em> mismatch: both references known, and different
     * (ADR-056). An unknown on either side is counted and logged for a person, never guessed at.
     */
    void returnIfStray(String orderNumber, String chargeOfRecord, SettledCharge charge, long amountCents) {
        if (charge.isNamedBy(chargeOfRecord)) {
            return;
        }
        if (chargeOfRecord == null || charge.gatewayReference() == null || charge.transactionReference() == null) {
            straysUnaccounted.increment();
            log.error(
                    "UNACCOUNTED CHARGE {} ({} cents) against order {}, which names {} — manual reconciliation"
                            + " required",
                    charge.gatewayReference(),
                    amountCents,
                    orderNumber,
                    chargeOfRecord);
            return;
        }

        RefundResult result = payments.refund(
                charge.transactionReference(), amountCents, "a second charge for one reservation");
        if (result.succeeded()) {
            straysReturned.increment();
            log.warn(
                    "Returned a second charge {} ({} cents) for order {}, which kept {}",
                    charge.gatewayReference(),
                    amountCents,
                    orderNumber,
                    chargeOfRecord);
            return;
        }
        straysRefused.increment();
        log.error(
                "REFUND FAILED for a second charge {} ({} cents) against order {}: {} — manual reconciliation"
                        + " required",
                charge.gatewayReference(),
                amountCents,
                orderNumber,
                result.failureReason());
    }

    private static Counter strayCounter(MeterRegistry meters, String outcome) {
        return Counter.builder(STRAY_CHARGES)
                .description("Second settled charges for one reservation, by what became of them")
                .tag("outcome", outcome)
                .register(meters);
    }
}
