package com.flashseats.hold.model;

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
 * A reservation. <strong>This row is the authority</strong> for its own lifecycle — not a cache, not
 * a mirror of something in Redis (ADR-019).
 */
@Entity
@Table(name = "ticket_holds")
@Getter
@Setter
@NoArgsConstructor
public class TicketHold {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hold_token", nullable = false, unique = true, length = 64)
    private String holdToken;

    @Column(name = "user_session_id", nullable = false)
    private String userSessionId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "tier_id", nullable = false)
    private Long tierId;

    @Column(nullable = false)
    private int quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private HoldStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    /** Enforces the one-extension ceiling directly in the claim's {@code WHERE} clause. */
    @Column(name = "extended_count", nullable = false)
    private int extendedCount;

    @Column(name = "settled_at")
    private Instant settledAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "settle_reason", length = 64)
    private SettleReason settleReason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public TicketHold(
            String holdToken,
            String userSessionId,
            long eventId,
            long tierId,
            int quantity,
            Instant expiresAt) {
        this.holdToken = holdToken;
        this.userSessionId = userSessionId;
        this.eventId = eventId;
        this.tierId = tierId;
        this.quantity = quantity;
        this.expiresAt = expiresAt;
        this.status = HoldStatus.ACTIVE;
    }

    public boolean isActive() {
        return status == HoldStatus.ACTIVE;
    }
}
