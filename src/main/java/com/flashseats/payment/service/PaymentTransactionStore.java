package com.flashseats.payment.service;

import com.flashseats.payment.facade.AuthorizeCommand;
import com.flashseats.payment.gateway.GatewayResult;
import com.flashseats.payment.model.PaymentStatus;
import com.flashseats.payment.model.PaymentTransaction;
import com.flashseats.payment.repository.PaymentTransactionRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The <strong>short</strong> transactions that bracket a gateway call. A separate bean because
 * Spring's proxy does not intercept self-invocation. {@code REQUIRES_NEW}, so an attempt's record
 * survives whatever the caller decides next.
 */
@Component
public class PaymentTransactionStore {

    private final PaymentTransactionRepository transactions;

    public PaymentTransactionStore(PaymentTransactionRepository transactions) {
        this.transactions = transactions;
    }

    /**
     * Opens one charge attempt: resume the intent this hold is authenticating, or record a new one. It
     * is one transaction, not two, so the 3-D Secure minority adds no transaction to every checkout
     * (ADR-049, ADR-051).
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChargeAttempt beginAttempt(AuthorizeCommand command) {
        Optional<PaymentTransaction> resumable = transactions
                .findFirstByHoldTokenAndStatusOrderByIdDesc(command.holdToken(), PaymentStatus.PROCESSING)
                .filter(transaction -> transaction.getGatewayReference() != null);

        if (resumable.isPresent()) {
            PaymentTransaction pending = resumable.get();
            return new ChargeAttempt(pending.getTransactionReference(), pending.getGatewayReference());
        }

        PaymentTransaction transaction = new PaymentTransaction(
                "pt_" + UUID.randomUUID().toString().replace("-", ""),
                command.orderNumber(),
                command.holdToken(),
                command.userSessionId(),
                command.amountCents(),
                command.currency(),
                command.clientIdempotencyKey(),
                command.attemptNumber());
        return new ChargeAttempt(transactions.save(transaction).getTransactionReference(), null);
    }

    /**
     * Records what the provider answered. {@code REQUIRES_ACTION} lands as
     * {@link PaymentStatus#PROCESSING}, which is what {@link #beginAttempt} resumes. The gateway
     * reference is only ever overwritten, never cleared: it is the id support and reconciliation work
     * from.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordOutcome(String transactionReference, GatewayResult result) {
        transactions.findByTransactionReference(transactionReference).ifPresent(transaction -> {
            transaction.setStatus(statusOf(result));
            if (result.gatewayReference() != null) {
                transaction.setGatewayReference(result.gatewayReference());
            }
            transaction.setFailureCode(result.failureCode());
            transaction.setFailureReason(result.failureReason());
        });
    }

    private static PaymentStatus statusOf(GatewayResult result) {
        return switch (result.outcome()) {
            case SUCCEEDED -> PaymentStatus.SUCCEEDED;
            case REQUIRES_ACTION -> PaymentStatus.PROCESSING;
            case DECLINED, ERROR -> PaymentStatus.FAILED;
        };
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRefund(
            String transactionReference,
            long amountCents) {

        transactions.findByTransactionReference(transactionReference)
                .ifPresent(transaction -> {

                    long alreadyRefunded =
                            transaction.getRefundedAmountCents();

                    /*
                    * This system performs full refunds.
                    *
                    * If another recovery path reaches this method after the
                    * refund was already recorded, do not add the amount again.
                    */
                    if (alreadyRefunded >= amountCents) {
                        return;
                    }

                    transaction.setRefundedAmountCents(
                            alreadyRefunded + amountCents);

                    transaction.setStatus(PaymentStatus.REFUNDED);
                });
    }

    /**
     * This module's reference for a provider intent id.
     *
     * <p>The webhook knows only what the provider told it. A settlement that has to be refunded
     * (ADR-012) needs the internal reference, and this is the only translation between the two.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<String> referenceForGateway(String gatewayReference) {
        return transactions
                .findByGatewayReference(gatewayReference)
                .map(PaymentTransaction::getTransactionReference);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public PaymentTransaction require(String transactionReference) {
        return transactions
                .findByTransactionReference(transactionReference)
                .orElseThrow(() ->
                        new IllegalStateException("No payment transaction " + transactionReference));
    }
}
