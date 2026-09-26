package com.flashseats.order.service;

import com.flashseats.payment.facade.PaymentFacade;
import com.flashseats.payment.facade.RefundResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Gives money back when the seats could not be delivered (ADR-012). Reached from
 * {@link CheckoutService} (the commit failed after the charge) and {@link PaymentSettlementService}
 * (a webhook for a hold already gone): one compensation, two triggers.
 *
 * <p>Not {@code @Transactional}: it calls the provider (ADR-023). A refused refund is still recorded
 * as {@code REFUNDED}, since the seats are gone, but the failure is written to {@code failure_reason}
 * and counted, so money we still hold surfaces for a human rather than being described as returned.
 */
@Slf4j
@Service
public class OrderRefundService {

    /** Non-zero means money is owed to a buyer that automation could not return. Alarm on any. */
    public static final String FAILED_REFUNDS = "flashseats.payment.refund.failed";

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
     */
    public void refund(
            String orderNumber, String transactionReference, long amountCents, String reason) {

        if (transactionReference == null) {
            failedRefunds.increment();
            log.error(
                    "REFUND IMPOSSIBLE for order {} ({} cents): no payment ledger row to refund against"
                            + " — manual reconciliation required",
                    orderNumber,
                    amountCents);
            commit.markRefunded(orderNumber, "refund not issued: no payment transaction found");
            return;
        }

        log.error(
                "Order {} could not be completed after a settled charge — refunding {} cents ({})",
                orderNumber,
                amountCents,
                reason);

        RefundResult result = payments.refund(transactionReference, amountCents, reason);

        if (result.succeeded()) {
            commit.markRefunded(orderNumber, reason);
            return;
        }

        failedRefunds.increment();
        log.error(
                "REFUND FAILED for order {} against {} ({} cents): {} — manual reconciliation required",
                orderNumber,
                transactionReference,
                amountCents,
                result.failureReason());
        commit.markRefunded(orderNumber, "refund failed: " + result.failureReason());
    }
}
