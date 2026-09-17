package com.flashseats.order.service;

import com.flashseats.order.repository.OutboxEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Publishes a bounded, periodically refreshed view of fulfilment backlog age. */
@Component
public class OutboxMetrics {

    private final OutboxEventRepository outbox;
    private final Clock clock;
    private final AtomicLong lagSeconds = new AtomicLong();

    public OutboxMetrics(OutboxEventRepository outbox, Clock clock, MeterRegistry meters) {
        this.outbox = outbox;
        this.clock = clock;
        Gauge.builder("flashseats.outbox.lag.seconds", lagSeconds, AtomicLong::get)
                .description("Age of the oldest unprocessed outbox event")
                .register(meters);
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    @Transactional(readOnly = true)
    public void refresh() {
        Instant oldest = outbox.oldestUnprocessedCreatedAt().orElse(null);
        long seconds = oldest == null
                ? 0
                : Math.max(0, Duration.between(oldest, clock.instant()).getSeconds());
        lagSeconds.set(seconds);
    }
}
