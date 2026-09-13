package com.flashseats.order.service;

import com.flashseats.payment.facade.PaymentFacade;
import com.flashseats.payment.facade.RefundResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Gives money back when the seats could not be delivered (ADR-012).
 *
 * <p>Two callers reach this, and they are the two halves of the same failure. {@link CheckoutService}
 * gets here when the charge settled and the commit then failed — typically a concurrent expiry won
 * the seats. {@link PaymentSettlementService} gets here when a webhook arrives for a hold that is
 * already gone. Same compensation, same outcome for the buyer; only the trigger differs, which is
 * why it lives in one place rather than being written twice.
 *
 * <p>Not {@code @Transactional} and it must not become so: it makes a network call to the provider
 * (ADR-023). {@link OrderCommitService#markRefunded} owns the transaction that follows.
 *
 * <p><strong>A failed refund is not recorded as a refund.</strong> The earlier version of this path
 * discarded {@link RefundResult} entirely, so a provider that refused the refund still produced an
 * order marked {@code REFUNDED} and an email telling the buyer their money was on its way. That is
 * money this business is holding and should not be, described to the only person who would notice as
 * already returned. The order is still moved to {@code REFUNDED} — the seats really are gone and no
 * other state is truer — but the failure is written into {@code failure_reason} and counted, so it
 * surfaces as something a human has to settle rather than as silence.
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
