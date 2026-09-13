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

    /**
     * Records what the provider answered.
     *
     * <p>{@code REQUIRES_ACTION} lands as {@link PaymentStatus#PROCESSING} — the state that was
     * declared on day one and never written, because the stub had no way to reach it. It is what
     * {@link #findResumable} looks for, so recording it correctly is what makes the 3-D Secure
     * resume find its intent rather than open a second one.
     *
     * <p>The gateway reference is only ever <em>overwritten</em>, never cleared: a decline arriving
     * against an intent that had already been created would otherwise erase the one id support and
     * reconciliation have to work from.
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

    /**
     * The intent this hold was already sent away to authenticate, if there is one.
     *
     * <p>Anchored on the hold token rather than the order number because that is the key the whole
     * idempotency scheme anchors on (ADR-014), and a resumed order keeps its number across retries
     * anyway.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public Optional<ResumableCharge> findResumable(String holdToken) {
        return transactions
                .findFirstByHoldTokenAndStatusOrderByIdDesc(holdToken, PaymentStatus.PROCESSING)
                .filter(transaction -> transaction.getGatewayReference() != null)
                .map(transaction -> new ResumableCharge(
                        transaction.getTransactionReference(), transaction.getGatewayReference()));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordRefund(String transactionReference, long amountCents) {
        transactions.findByTransactionReference(transactionReference).ifPresent(transaction -> {
            transaction.setStatus(PaymentStatus.REFUNDED);
            transaction.setRefundedAmountCents(transaction.getRefundedAmountCents() + amountCents);
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
