package com.flashseats.order.service;

import com.flashseats.order.model.OutboxEvent;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * Drains the outbox to the log ({@code flashseats.outbox.transport=log}, the test profile's
 * choice). A log line cannot fail, so every id comes back. A test using this exercises the relay's
 * bookkeeping, not delivery.
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
