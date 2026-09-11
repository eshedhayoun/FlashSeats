package com.flashseats.order.service;

import com.flashseats.order.model.OutboxEvent;
import java.util.List;
import java.util.UUID;

/**
 * Where claimed outbox rows are sent.
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
     * Sends a batch, and reports which events the transport <strong>durably accepted</strong>.
     *
     * <p><strong>The batch is the unit on purpose</strong>, and it is not a convenience. A broker
     * acknowledges asynchronously, so a one-event-at-a-time interface forces the caller to block on
     * each confirm in turn: a batch of a hundred against a sick broker becomes a hundred sequential
     * timeouts on the relay thread. Handing the whole batch over lets an implementation send
     * everything and then wait once.
     *
     * <p>Partial success is normal and is expressed as a <strong>return value, not an exception</strong>.
     * Anything missing from the result stays {@code PROCESSING} and is returned to {@code PENDING} by
     * the stale-claim sweep, so an event is retried rather than lost. Returning fewer ids than were
     * passed is therefore a routine outcome, never an error.
     *
     * @param events the claimed rows, in the order they were created
     * @return the ids that were durably accepted; may be empty, never null
     */
    List<UUID> publish(List<OutboxEvent> events);
}
