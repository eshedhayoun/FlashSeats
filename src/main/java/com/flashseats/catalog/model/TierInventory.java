package com.flashseats.catalog.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * The live remaining count for one tier — <strong>the number the whole system protects</strong>.
 *
 * <p>It is never read-then-written. Every movement is one conditional statement
 * ({@code CatalogTierInventoryRepository.tryReserve}) whose {@code WHERE} clause carries the
 * precondition, so PostgreSQL's row lock does the mutual exclusion and {@code CHECK (remaining >= 0)}
 * backstops it. That is why overbooking is impossible without any distributed coordination.
 */
@Entity
@Table(name = "tier_inventory")
@Getter
@Setter
@NoArgsConstructor
public class TierInventory {

    /** The tier id is the primary key — a reserve is a single-row lookup and lock. */
    @Id
    @Column(name = "tier_id")
    private Long tierId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(nullable = false)
    private int remaining;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public TierInventory(Long tierId, Long eventId, int remaining) {
        this.tierId = tierId;
        this.eventId = eventId;
        this.remaining = remaining;
    }
}
