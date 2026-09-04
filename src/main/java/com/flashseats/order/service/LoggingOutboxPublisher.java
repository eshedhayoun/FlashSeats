package com.flashseats.order.service;

import com.flashseats.order.model.OutboxEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Drains the outbox to the log.
 *
 * <p>Lets the relay — claim, publish, mark, and the stale-claim sweep — be built and tested from the
 * first line of code, before a broker exists. When {@code notification} arrives it registers its own
 * {@link OutboxPublisher} bean and this one steps aside untouched.
 */
public class LoggingOutboxPublisher implements OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(LoggingOutboxPublisher.class);

    @Override
    public void publish(OutboxEvent event) {
        log.info(
                "Outbox → (no broker configured) {} for {}: {}",
                event.getEventType(),
                event.getAggregateId(),
                event.getPayload());
    }
}
