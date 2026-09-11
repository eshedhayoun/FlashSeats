package com.flashseats.order.service;

import com.flashseats.order.model.OutboxEvent;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Drains the outbox to the log.
 *
 * <p>Lets the relay — claim, publish, mark, and the stale-claim sweep — be built and tested from the
 * first line of code, before a broker exists. When {@code notification} arrives it registers its own
 * {@link OutboxPublisher} bean and this one steps aside untouched.
 *
 * <p>A log line cannot fail to be durable, so every id comes back. That makes this the one
 * implementation for which partial success is impossible — worth knowing when reading a test that
 * uses it, because it exercises the relay's bookkeeping and nothing about delivery.
 */
@Slf4j
public class LoggingOutboxPublisher implements OutboxPublisher {

    @Override
    public List<UUID> publish(List<OutboxEvent> events) {
        for (OutboxEvent event : events) {
            log.info(
                    "Outbox → (no broker configured) {} for {}: {}",
                    event.getEventType(),
                    event.getAggregateId(),
                    event.getPayload());
        }
        return events.stream().map(OutboxEvent::getId).toList();
    }
}
