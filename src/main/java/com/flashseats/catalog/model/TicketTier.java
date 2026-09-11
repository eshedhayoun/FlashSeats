package com.flashseats.catalog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A price band within an event.
 *
 * <p>{@code priceCents} is the <strong>only</strong> source of a charge amount. No client input ever
 * contributes to what a buyer is charged (ADR-013).
 */
@Entity
@Table(name = "ticket_tiers")
@Getter
@Setter
@NoArgsConstructor
public class TicketTier {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "tier_name", nullable = false, length = 100)
    private String tierName;

    @Column(name = "price_cents", nullable = false)
    private long priceCents;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Immutable once the sale opens; changing it requires pause, change, rebuild. */
    @Column(name = "total_capacity", nullable = false)
    private int totalCapacity;

    /** Server-authoritative order cap (ADR-017). The UI renders this, never a hardcoded number. */
    @Column(name = "max_per_order", nullable = false)
    private int maxPerOrder;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
