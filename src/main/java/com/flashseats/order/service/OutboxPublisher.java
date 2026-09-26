package com.flashseats.order.service;

import com.flashseats.order.model.OutboxEvent;
import java.util.List;
import java.util.UUID;

/**
 * Where claimed outbox rows are sent, chosen by {@code flashseats.outbox.transport}, so
 * {@code order} never learns the transport. Always called <strong>outside every transaction</strong>:
 * a broker round trip holding row locks throttles checkout (ADR-023).
 */
public interface OutboxPublisher {

    /**
     * Sends a batch, and reports which events the transport <strong>durably accepted</strong>. The batch
     * is the unit so an implementation can send everything and await confirms once, rather than
     * blocking per event against a slow broker. Partial success is a return value, not an exception:
     * anything missing stays {@code PROCESSING} and the stale-claim sweep retries it.
     *
     * @param events the claimed rows, in the order they were created
     * @return the ids that were durably accepted; may be empty, never null
     */
    List<UUID> publish(List<OutboxEvent> events);
}
