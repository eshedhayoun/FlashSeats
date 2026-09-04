package com.flashseats.payment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** The durable record of one charge attempt: what the gateway was asked, and what it answered. */
@Entity
@Table(name = "payment_transactions")
@Getter
@Setter
@NoArgsConstructor
public class PaymentTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "transaction_reference", nullable = false, unique = true, length = 64)
    private String transactionReference;

    @Column(name = "order_number", nullable = false, length = 64)
    private String orderNumber;

    @Column(name = "hold_token", nullable = false, length = 64)
    private String holdToken;

    @Column(name = "user_session_id", nullable = false)
    private String userSessionId;

    /** The gateway's own id for the charge. Named for Stripe, since that is what replaces the stub. */
    @Column(name = "stripe_payment_intent_id")
    private String gatewayReference;

    @Column(name = "client_idempotency_key", length = 64)
    private String clientIdempotencyKey;

    @Column(name = "amount_cents", nullable = false)
    private long amountCents;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "failure_code", length = 64)
    private String failureCode;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "attempt_number", nullable = false)
    private int attemptNumber;

    @Column(name = "refunded_amount_cents", nullable = false)
    private long refundedAmountCents;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public PaymentTransaction(
            String transactionReference,
            String orderNumber,
            String holdToken,
            String userSessionId,
            long amountCents,
            String currency,
            String clientIdempotencyKey,
            int attemptNumber) {
        this.transactionReference = transactionReference;
        this.orderNumber = orderNumber;
        this.holdToken = holdToken;
        this.userSessionId = userSessionId;
        this.amountCents = amountCents;
        this.currency = currency;
        this.clientIdempotencyKey = clientIdempotencyKey;
        this.attemptNumber = attemptNumber;
        this.status = PaymentStatus.INITIATED;
    }
}
