package com.flashseats.order.service;

import com.flashseats.order.model.OutboxEvent;
import lombok.extern.slf4j.Slf4j;

/**
 * Drains the outbox to the log.
 *
 * <p>Lets the relay — claim, publish, mark, and the stale-claim sweep — be built and tested from the
 * first line of code, before a broker exists. When {@code notification} arrives it registers its own
 * {@link OutboxPublisher} bean and this one steps aside untouched.
 */
@Slf4j
public class LoggingOutboxPublisher implements OutboxPublisher {

    @Override
    public void publish(OutboxEvent event) {
        log.info(
                "Outbox → (no broker configured) {} for {}: {}",
                event.getEventType(),
                event.getAggregateId(),
                event.getPayload());
    }
}
