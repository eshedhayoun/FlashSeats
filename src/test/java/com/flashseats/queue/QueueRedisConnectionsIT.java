package com.flashseats.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flashseats.app.support.IntegrationTest;
import com.flashseats.app.support.SaleFixture;
import com.flashseats.queue.facade.QueuePhase;
import com.flashseats.queue.service.QueueService;
import io.lettuce.core.event.connection.ConnectionActivatedEvent;
import io.lettuce.core.resource.ClientResources;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import reactor.core.Disposable;

/**
 * The waiting room's Redis traffic stays on the shared connection (ADR-079).
 *
 * <p>Both of these paths were pipelines, and a Lettuce pipeline cannot use the shared, multiplexed
 * connection. With no pool configured Spring opened a fresh one for every call and closed it after,
 * and under Sentinel each of those began with a second connection to ask where the primary was. The
 * status read is the most-called path in the system, so at 10,000 buyers polling it the cluster spent
 * its CPU on TCP handshakes, and nothing failed: requests just took 30 seconds.
 *
 * <p>Counted from the driver's own events, not from Redis: the server's connection counter also sees
 * every other context the suite has cached, and this context's client sees only this context.
 */
@DisplayName("The waiting room never opens a Redis connection per call")
class QueueRedisConnectionsIT extends IntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(15);

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private QueueService queue;

    @Autowired
    private ClientResources redisClientResources;

    @BeforeEach
    void reset() {
        fixture.reset();
    }

    @Test
    @DisplayName("Two hundred status reads open no connection")
    void statusReadsUseTheSharedConnection() {
        // No counter, so the promotion worker leaves this event alone while it is measured.
        long eventId = fixture.openEvent("Status Reads");
        fixture.tierWithoutCounter(eventId, "General Admission", 2_500, 100);
        queue.join("reader-in-line", eventId, null, null);

        int opened = connectionsOpenedDuring(() -> {
            assertThat(queue.getQueueState("reader-in-line", eventId).phase()).isEqualTo(QueuePhase.WAITING);
            for (int i = 0; i < 200; i++) {
                assertThat(queue.getQueueState("reader-" + i, eventId).phase()).isEqualTo(QueuePhase.NOT_JOINED);
            }
        });

        assertThat(opened).describedAs("connections opened by 201 status reads").isZero();
    }

    @Test
    @DisplayName("Promoting a line of buyers opens no connection")
    void promotionUsesTheSharedConnection() {
        long eventId = fixture.openEvent("Promotion");
        fixture.tier(eventId, "Floor", 4_500, 50);
        List<String> buyers = IntStream.range(0, 10).mapToObj(i -> "promoted-" + i).toList();

        int opened = connectionsOpenedDuring(() -> {
            buyers.forEach(buyer -> queue.join(buyer, eventId, null, null));
            await().atMost(PATIENCE).untilAsserted(() -> assertThat(buyers)
                    .allSatisfy(buyer -> assertThat(queue.getQueueState(buyer, eventId).phase())
                            .isEqualTo(QueuePhase.PROMOTED)));
        });

        assertThat(opened).describedAs("connections opened while ten buyers were promoted").isZero();
    }

    private int connectionsOpenedDuring(Runnable work) {
        AtomicInteger opened = new AtomicInteger();
        Disposable listening = redisClientResources.eventBus().get()
                .filter(ConnectionActivatedEvent.class::isInstance)
                .subscribe(event -> opened.incrementAndGet());
        try {
            work.run();
        } finally {
            listening.dispose();
        }
        return opened.get();
    }
}
