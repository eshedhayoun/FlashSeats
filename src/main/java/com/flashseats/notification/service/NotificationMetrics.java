package com.flashseats.notification.service;

import com.flashseats.notification.model.NotificationStatus;
import com.flashseats.notification.repository.NotificationLogRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Publishes the current notification dead-letter backlog without querying on scrape. */
@Component
public class NotificationMetrics {

    private final NotificationLogRepository logs;
    private final AtomicLong depth = new AtomicLong();

    public NotificationMetrics(NotificationLogRepository logs, MeterRegistry meters) {
        this.logs = logs;
        Gauge.builder("flashseats.dlq.depth", depth, AtomicLong::get)
                .description("Notifications waiting for operator replay")
                .tag("queue", "notification")
                .register(meters);
    }

    @Scheduled(fixedDelay = 10_000, initialDelay = 10_000)
    @Transactional(readOnly = true)
    public void refresh() {
        depth.set(logs.countByStatus(NotificationStatus.DLQ));
    }
}
