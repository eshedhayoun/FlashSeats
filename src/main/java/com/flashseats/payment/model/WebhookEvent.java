package com.flashseats.payment.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One delivery from the provider, claimed so that the next copy of it does nothing.
 *
 * <p>The provider's id is the primary key, and that is the whole replay guard. There is no
 * surrogate key and no unique index alongside it: a second identity would be a second thing to keep
 * in step.
 */
@Entity
@Table(name = "webhook_events")
@Getter
@Setter
@NoArgsConstructor
public class WebhookEvent {

    @Id
    @Column(name = "stripe_event_id", length = 64)
    private String stripeEventId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "payment_intent_id")
    private String paymentIntentId;

    @Column(name = "hold_token", length = 64)
    private String holdToken;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    /** {@code null} means in flight. A delivery that failed has no row at all — it was released. */
    @Column(name = "processed_at")
    private Instant processedAt;
}
