package com.flashseats.order.service;

import com.flashseats.order.model.OutboxEvent;

/**
 * Where a claimed outbox row is sent.
 *
 * <p>The seam exists so {@code order} never learns what the transport is. The MVP logs; the broker
 * implementation replaces this bean and no code in this module changes.
 *
 * <p>Implementations are called <strong>outside every transaction</strong>. Publishing inside the
 * claim transaction would hold row locks across a network round trip to the broker, and under
 * virtual threads that throttles checkout for everyone (ADR-023).
 */
public interface OutboxPublisher {

    /**
     * Sends one event.
     *
     * @throws RuntimeException if the send failed; the row stays {@code PROCESSING} and is re-swept
     */
    void publish(OutboxEvent event);
}
