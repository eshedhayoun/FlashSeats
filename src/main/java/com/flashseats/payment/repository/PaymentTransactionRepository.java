package com.flashseats.payment.repository;

import com.flashseats.payment.model.PaymentStatus;
import com.flashseats.payment.model.PaymentTransaction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findByTransactionReference(String transactionReference);

    /**
     * The most recent attempt for a hold in a given state.
     *
     * <p>Used with {@link PaymentStatus#PROCESSING} to find an intent the buyer was sent away to
     * authenticate, so the resume re-reads <em>that</em> charge instead of starting another one.
     * Newest first, because a hold may have been declined twice before the card that asked for 3-D
     * Secure.
     */
    Optional<PaymentTransaction> findFirstByHoldTokenAndStatusOrderByIdDesc(
            String holdToken, PaymentStatus status);

    /**
     * The ledger row for a provider intent id.
     *
     * <p>The webhook arrives knowing only what the provider knows, and a refund needs this module's
     * own reference. {@code stripe_payment_intent_id} is {@code UNIQUE} in {@code V4}, so this is
     * single-valued by construction.
     */
    Optional<PaymentTransaction> findByGatewayReference(String gatewayReference);
}
