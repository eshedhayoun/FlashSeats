package com.flashseats.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.app.support.IntegrationTest;
import com.flashseats.app.support.SaleFixture;
import com.flashseats.order.model.OutboxEvent;
import com.flashseats.order.model.OutboxStatus;
import com.flashseats.order.repository.OutboxEventRepository;
import com.flashseats.order.service.OutboxStore;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Verifies that an old outbox relay cannot complete a claim owned by a newer relay.
 *
 * <p>The retry count is the claim generation. Every stale-claim recovery increments it, so a relay
 * that wakes up after losing its claim can still publish its message at least once, but cannot mark
 * the replacement claim as processed.
 */
@DisplayName("Outbox claim generations prevent stale relays from completing newer claims")
class OutboxClaimGenerationIT extends IntegrationTest {

    @Autowired
    private OutboxEventRepository outbox;

    @Autowired
    private OutboxStore store;

    @Autowired
    private SaleFixture fixture;

    @BeforeEach
    void resetDatabase() {
        fixture.reset();
    }

    @Test
    @DisplayName("A stale claim is recovered into a new claim generation")
    void staleClaimCreatesNewGeneration() {
        OutboxEvent event = processingEvent("TK-GENERATION-1");
        event.setClaimedAt(Instant.now().minusSeconds(120));

        outbox.saveAndFlush(event);

        assertThat(store.releaseStaleClaims()).isEqualTo(1);

        OutboxEvent recovered = outbox.findById(event.getId()).orElseThrow();

        assertThat(recovered.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(recovered.getClaimedAt()).isNull();
        assertThat(recovered.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("An old relay cannot mark the newer claim as processed")
    void oldRelayCannotCompleteNewerClaim() {
        OutboxEvent event = processingEvent("TK-GENERATION-2");
        event.setClaimedAt(Instant.now().minusSeconds(120));

        outbox.saveAndFlush(event);

        // Relay A owns generation 0.
        int released = store.releaseStaleClaims();
        assertThat(released).isEqualTo(1);

        OutboxEvent recovered = outbox.findById(event.getId()).orElseThrow();

        // Relay B takes the recovered event as generation 1.
        recovered.setStatus(OutboxStatus.PROCESSING);
        recovered.setClaimedAt(Instant.now());
        outbox.saveAndFlush(recovered);

        assertThat(recovered.getRetryCount()).isEqualTo(1);

        // Relay A finally wakes up and reports that its original publish succeeded.
        // It still carries generation 0, so it must NOT complete Relay B's claim.
        store.markProcessed(List.of(event));

        OutboxEvent stillProcessing = outbox.findById(event.getId()).orElseThrow();

        assertThat(stillProcessing.getStatus())
                .isEqualTo(OutboxStatus.PROCESSING);
        assertThat(stillProcessing.getRetryCount())
                .isEqualTo(1);

        // Relay B owns generation 1 and is therefore allowed to complete it.
        store.markProcessed(List.of(stillProcessing));

        OutboxEvent processed = outbox.findById(event.getId()).orElseThrow();

        assertThat(processed.getStatus())
                .isEqualTo(OutboxStatus.PROCESSED);
        assertThat(processed.getRetryCount())
                .isEqualTo(1);
        assertThat(processed.getProcessedAt())
                .isNotNull();
    }

    private OutboxEvent processingEvent(String orderNumber) {
        OutboxEvent event = new OutboxEvent(
                "Order",
                orderNumber,
                "ORDER_CONFIRMED",
                "{\"orderNumber\":\"" + orderNumber + "\"}");

        event.setStatus(OutboxStatus.PROCESSING);
        event.setRetryCount(0);

        return event;
    }
}