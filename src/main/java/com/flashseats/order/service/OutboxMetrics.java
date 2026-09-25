package com.flashseats.order.service;

import com.flashseats.order.model.OutboxStatus;
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

    /**
     * Unprocessed means {@code PENDING} <em>or</em> {@code PROCESSING}, asked one status at a time.
     *
     * <p>A single {@code status <> PROCESSED} read looks tidier and cannot use either of this table's
     * partial indexes, so it sequentially scanned every row including the processed ones awaiting
     * purge — see {@link OutboxEventRepository#oldestCreatedAtWithStatus}. A claimed-but-unpublished
     * row is still backlog, so both statuses count and the older of the two wins.
     */
    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    @Transactional(readOnly = true)
    public void refresh() {
        Instant oldest = oldest(OutboxStatus.PENDING, OutboxStatus.PROCESSING);
        long seconds = oldest == null
                ? 0
                : Math.max(0, Duration.between(oldest, clock.instant()).getSeconds());
        lagSeconds.set(seconds);
    }

    private Instant oldest(OutboxStatus... statuses) {
        Instant oldest = null;
        for (OutboxStatus status : statuses) {
            Instant candidate = outbox.oldestCreatedAtWithStatus(status).orElse(null);
            if (candidate != null && (oldest == null || candidate.isBefore(oldest))) {
                oldest = candidate;
            }
        }
        return oldest;
    }
}
