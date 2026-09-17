package com.flashseats.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flashseats.notification.model.NotificationStatus;
import com.flashseats.notification.repository.NotificationLogRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class NotificationMetricsTest {

    private final NotificationLogRepository logs = mock(NotificationLogRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final NotificationMetrics metrics = new NotificationMetrics(logs, meters);

    @Test
    void reportsNotificationDeadLetterDepth() {
        when(logs.countByStatus(NotificationStatus.DLQ)).thenReturn(3L);

        metrics.refresh();

        assertThat(meters.get("flashseats.dlq.depth").tag("queue", "notification").gauge().value())
                .isEqualTo(3.0);
    }

    @Test
    void reportsZeroWhenDeadLetterQueueIsEmpty() {
        when(logs.countByStatus(NotificationStatus.DLQ)).thenReturn(0L);

        metrics.refresh();

        assertThat(meters.get("flashseats.dlq.depth").tag("queue", "notification").gauge().value())
                .isZero();
    }
}
