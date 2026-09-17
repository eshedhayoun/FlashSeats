package com.flashseats.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flashseats.order.repository.OutboxEventRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OutboxMetricsTest {

    private final OutboxEventRepository outbox = mock(OutboxEventRepository.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final OutboxMetrics metrics = new OutboxMetrics(
            outbox,
            Clock.fixed(Instant.parse("2026-09-17T12:00:10Z"), ZoneOffset.UTC),
            meters);

    @Test
    void reportsAgeOfOldestUnprocessedEvent() {
        when(outbox.oldestUnprocessedCreatedAt())
                .thenReturn(Optional.of(Instant.parse("2026-09-17T11:59:00Z")));

        metrics.refresh();

        assertThat(meters.get("flashseats.outbox.lag.seconds").gauge().value()).isEqualTo(70.0);
    }

    @Test
    void reportsZeroWhenOutboxIsCaughtUp() {
        when(outbox.oldestUnprocessedCreatedAt()).thenReturn(Optional.empty());

        metrics.refresh();

        assertThat(meters.get("flashseats.outbox.lag.seconds").gauge().value()).isZero();
    }
}
