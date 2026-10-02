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

    public OrderRefundService(PaymentFacade payments, OrderCommitService commit, MeterRegistry meters) {
        this.payments = payments;
        this.commit = commit;
        this.failedRefunds = Counter.builder(FAILED_REFUNDS)
                .description("Refunds the provider refused. Each one is money owed to a named buyer.")
                .register(meters);
    }

    /**
     * @param transactionReference this module's payment reference, or {@code null} if the ledger row
     *     could not be found — which can only happen if the charge was made by something other than
     *     this system, and is recorded rather than swallowed
     * @return how it ended; {@link Outcome#RESOLVED_ELSEWHERE} means no money moved here
     */
    public Outcome refund(
            String orderNumber, String transactionReference, long amountCents, String reason) {

        if (!commit.claimRefund(orderNumber, reason)) {
            log.info("Order {} was already resolved by the other settlement path; nothing to refund", orderNumber);
            return Outcome.RESOLVED_ELSEWHERE;
        }

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
}
