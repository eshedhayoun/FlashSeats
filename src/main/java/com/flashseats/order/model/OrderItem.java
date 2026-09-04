package com.flashseats.order.model;

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

/**
 * One line of a purchase.
 *
 * <p>{@code tierName} and {@code unitPriceCents} are <strong>snapshots</strong>, not references. A
 * receipt must still say what the buyer actually paid for even after the tier is renamed or
 * repriced.
 */
@Entity
@Table(name = "order_items")
@Getter
@Setter
@NoArgsConstructor
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "event_id", nullable = false)
    private Long eventId;

    @Column(name = "tier_id", nullable = false)
    private Long tierId;

    @Column(name = "tier_name", nullable = false, length = 100)
    private String tierName;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "unit_price_cents", nullable = false)
    private long unitPriceCents;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public OrderItem(
            Long orderId, long eventId, long tierId, String tierName, int quantity, long unitPriceCents) {
        this.orderId = orderId;
        this.eventId = eventId;
        this.tierId = tierId;
        this.tierName = tierName;
        this.quantity = quantity;
        this.unitPriceCents = unitPriceCents;
    }
}
