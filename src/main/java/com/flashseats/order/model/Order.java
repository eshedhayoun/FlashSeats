package com.flashseats.order.model;

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

/**
 * A purchase.
 *
 * <p><strong>{@code holdToken} is {@code UNIQUE}, and that constraint is the strongest overbooking
 * guard in the system</strong> (ADR-002): one reservation can never become two orders, however many
 * times a client submits, retries, or double-clicks. It is also what makes checkout a find-or-create
 * rather than an insert, and what a gateway callback correlates against.
 */
@Entity
@Table(name = "orders")
@Getter
@Setter
@NoArgsConstructor
public class Order {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_number", nullable = false, unique = true, length = 64)
    private String orderNumber;

    @Column(name = "hold_token", nullable = false, unique = true, length = 64)
    private String holdToken;

    @Column(name = "user_session_id", nullable = false)
    private String userSessionId;

    @Column(name = "user_email", nullable = false)
    private String userEmail;

    /** Signed capability: it, or a matching session cookie, is what authorises reading this order. */
    @Column(name = "receipt_token", nullable = false)
    private String receiptToken;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "total_amount_cents", nullable = false)
    private long totalAmountCents;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private OrderStatus status;

    @Column(name = "payment_transaction_ref", length = 64)
    private String paymentTransactionRef;

    @Column(name = "stripe_payment_intent_id")
    private String gatewayReference;

    /** Capped at three (ADR-014); retrying a decline changes nothing but the fraud signal. */
    @Column(name = "payment_attempts", nullable = false)
    private int paymentAttempts;

    @Column(name = "failure_reason")
    private String failureReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public Order(
            String orderNumber,
            String holdToken,
            String userSessionId,
            String userEmail,
            String receiptToken,
            long eventId,
            long totalAmountCents,
            String currency) {
        this.orderNumber = orderNumber;
        this.holdToken = holdToken;
        this.userSessionId = userSessionId;
        this.userEmail = userEmail;
        this.receiptToken = receiptToken;
        this.eventId = eventId;
        this.totalAmountCents = totalAmountCents;
        this.currency = currency;
        this.status = OrderStatus.PENDING;
    }
}
