package com.flashseats.flashseats.hold.model;
import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

@Entity
@Table(
    name = "ticket_holds",
    indexes={
        @Index(name = "idx_ticket_holds_token",columnList = "hold_token",unique=true),
        @Index(name = "idx_ticket_holds_session",columnList = "user_session_id"),
        @Index(name = "idx_ticket_holds_event_tier",columnList = "event_id,tier_id"),
        /*needs to add @Index(
            name = "idx_ticket_holds_status_expires",
            columnList = "status,expires_at"
        )
            that reflect
            CREATE INDEX idx_ticket_holds_status_expires ON ticket_holds(status, expires_at) 
            WHERE status = 'ACTIVE';
         */
    }
)
public class TicketHoldEntity {
    

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "hold_token", nullable = false, unique = true, length = 64)
    private String holdToken;

    @Column(name = "user_session_id", nullable = false , length = 255)
    private String userSessionId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "tier_id", nullable = false)
    private Long tierId;
    
    @NotNull
    @Min(1)
    @Column(nullable = false)
    private Integer quantity;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private HoldStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected TicketHoldEntity() {
    }

    public TicketHoldEntity(
            String holdToken,
            String userSessionId,
            Long eventId,
            Long tierId,
            Integer quantity,
            HoldStatus status,
            Instant expiresAt
    ) {
        this.holdToken = holdToken;
        this.userSessionId = userSessionId;
        this.eventId = eventId;
        this.tierId = tierId;
        this.quantity = quantity;
        this.status = status;
        this.expiresAt = expiresAt;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getHoldToken() {
        return holdToken;
    }

    public String getUserSessionId() {
        return userSessionId;
    }

    public Long getEventId() {
        return eventId;
    }

    public Long getTierId() {
        return tierId;
    }

    public Integer getQuantity() {
        return quantity;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setStatus(HoldStatus status) {
        this.status = status;
    }
}
