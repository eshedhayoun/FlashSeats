package com.flashseats.payment.service;

import com.flashseats.payment.facade.AuthorizeCommand;
import com.flashseats.payment.gateway.GatewayResult;
import com.flashseats.payment.model.PaymentStatus;
import com.flashseats.payment.model.PaymentTransaction;
import com.flashseats.payment.repository.PaymentTransactionRepository;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two <strong>short</strong> transactions that bracket a gateway call.
 *
 * <p>They live on their own bean rather than as private methods on {@link PaymentService} because
 * Spring's transaction proxy does not intercept self-invocation: a {@code @Transactional} method
 * called from inside the same object runs with no transaction at all, silently. Splitting the class
 * is what makes the boundary real, and it makes it visible in the code as well.
 *
 * <p>{@code REQUIRES_NEW} because the caller may already be inside a transaction; the record of an
 * attempt must survive independently of whatever the caller later decides to do.
 */
@Component
public class PaymentTransactionStore {

    private final PaymentTransactionRepository transactions;

    public PaymentTransactionStore(PaymentTransactionRepository transactions) {
        this.transactions = transactions;
    }

    /** Records the intent to charge, before any network call. Committed immediately. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public PaymentTransaction recordInitiated(AuthorizeCommand command) {
        PaymentTransaction transaction = new PaymentTransaction(
                "pt_" + UUID.randomUUID().toString().replace("-", ""),
                command.orderNumber(),
                command.holdToken(),
                command.userSessionId(),
                command.amountCents(),
                command.currency(),
                command.clientIdempotencyKey(),
                command.attemptNumber());
        return transactions.save(transaction);
    }

    /** Records what the provider answered. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOutcome(String transactionReference, GatewayResult result) {
        transactions.findByTransactionReference(transactionReference).ifPresent(transaction -> {
            transaction.setStatus(result.isSuccess() ? PaymentStatus.SUCCEEDED : PaymentStatus.FAILED);
            transaction.setGatewayReference(result.gatewayReference());
            transaction.setFailureCode(result.failureCode());
            transaction.setFailureReason(result.failureReason());
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRefund(String transactionReference, long amountCents) {
        transactions.findByTransactionReference(transactionReference).ifPresent(transaction -> {
            transaction.setStatus(PaymentStatus.REFUNDED);
            transaction.setRefundedAmountCents(transaction.getRefundedAmountCents() + amountCents);
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PaymentTransaction require(String transactionReference) {
        return transactions
                .findByTransactionReference(transactionReference)
                .orElseThrow(() ->
                        new IllegalStateException("No payment transaction " + transactionReference));
    }
}
