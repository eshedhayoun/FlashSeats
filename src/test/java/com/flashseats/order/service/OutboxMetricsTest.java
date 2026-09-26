package com.flashseats.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flashseats.order.model.OutboxStatus;
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
        when(outbox.oldestCreatedAtWithStatus(OutboxStatus.PENDING))
                .thenReturn(Optional.of(Instant.parse("2026-09-17T11:59:00Z")));
        when(outbox.oldestCreatedAtWithStatus(OutboxStatus.PROCESSING))
                .thenReturn(Optional.empty());

        metrics.refresh();

        assertThat(meters.get("flashseats.outbox.lag.seconds").gauge().value()).isEqualTo(70.0);
    }

    @Test
    void reportsZeroWhenOutboxIsCaughtUp() {
        when(outbox.oldestCreatedAtWithStatus(OutboxStatus.PENDING)).thenReturn(Optional.empty());
        when(outbox.oldestCreatedAtWithStatus(OutboxStatus.PROCESSING)).thenReturn(Optional.empty());

        metrics.refresh();

        assertThat(meters.get("flashseats.outbox.lag.seconds").gauge().value()).isZero();
    }

    /**
     * A claimed row is still backlog.
     *
     * <p>The lag is now assembled from two reads rather than one {@code <> PROCESSED} scan, so the
     * thing that could silently break is forgetting one of them. A relay that claims a batch and then
     * cannot publish it holds the oldest work in the system, and a gauge that only looked at
     * {@code PENDING} would read zero at exactly the moment fulfilment had stopped.
     */
    @Test
    void countsClaimedButUnpublishedEventsAsBacklog() {
        when(outbox.oldestCreatedAtWithStatus(OutboxStatus.PENDING)).thenReturn(Optional.empty());
        when(outbox.oldestCreatedAtWithStatus(OutboxStatus.PROCESSING))
                .thenReturn(Optional.of(Instant.parse("2026-09-17T11:59:40Z")));

        metrics.refresh();

        assertThat(meters.get("flashseats.outbox.lag.seconds").gauge().value()).isEqualTo(30.0);
    }

    @Test
    void reportsTheOlderOfTheTwoStatuses() {
        when(outbox.oldestCreatedAtWithStatus(OutboxStatus.PENDING))
                .thenReturn(Optional.of(Instant.parse("2026-09-17T11:59:50Z")));
        when(outbox.oldestCreatedAtWithStatus(OutboxStatus.PROCESSING))
                .thenReturn(Optional.of(Instant.parse("2026-09-17T11:59:00Z")));

        metrics.refresh();

        assertThat(meters.get("flashseats.outbox.lag.seconds").gauge().value()).isEqualTo(70.0);
    }
}
