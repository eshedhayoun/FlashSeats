package com.flashseats.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import com.flashseats.order.model.OutboxEvent;
import com.flashseats.order.model.OutboxStatus;
import com.flashseats.order.repository.OutboxEventRepository;
import com.flashseats.order.service.OutboxStore;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.List;

/**
 * Proves that a relay crash after claiming an outbox row does not lose the event.
 */

@DisplayName("A crashed outbox relay leaves the event recoverable")
class OutboxRecoveryIT extends IntegrationTest {

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
    @DisplayName("A stale PROCESSING claim returns to PENDING and increments its retry count")
    void staleClaimIsReturnedToPending() {
        OutboxEvent event = new OutboxEvent(
                "Order",
                "TK-STALE",
                "ORDER_CONFIRMED",
                "{\"orderNumber\":\"TK-STALE\"}");

        event.setStatus(OutboxStatus.PROCESSING);
        event.setClaimedAt(Instant.now().minusSeconds(120));

        outbox.saveAndFlush(event);

        int released = store.releaseStaleClaims();

        assertThat(released).isEqualTo(1);

        OutboxEvent recovered = outbox.findById(event.getId()).orElseThrow();

        assertThat(recovered.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(recovered.getClaimedAt()).isNull();
        assertThat(recovered.getRetryCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("A fresh PROCESSING claim is not returned to PENDING")
    void freshClaimIsNotRecovered() {
        OutboxEvent event = new OutboxEvent(
                "Order",
                "TK-FRESH",
                "ORDER_CONFIRMED",
                "{\"orderNumber\":\"TK-FRESH\"}");

        event.setStatus(OutboxStatus.PROCESSING);
        event.setClaimedAt(Instant.now());

        outbox.saveAndFlush(event);

        int released = store.releaseStaleClaims();

        assertThat(released).isZero();

        OutboxEvent untouched = outbox.findById(event.getId()).orElseThrow();

        assertThat(untouched.getStatus()).isEqualTo(OutboxStatus.PROCESSING);
        assertThat(untouched.getClaimedAt()).isNotNull();
        assertThat(untouched.getRetryCount()).isZero();
    }
    @Test
    @DisplayName("An old relay cannot mark a newer claim as processed")
    void oldRelayCannotCompleteNewerClaim() {
        OutboxEvent event = new OutboxEvent(
                "Order",
                "TK-RACE",
                "ORDER_CONFIRMED",
                "{\"orderNumber\":\"TK-RACE\"}");

        Instant oldClaim = Instant.now().minusSeconds(120);

        // This represents relay A's original claim.
        event.setStatus(OutboxStatus.PROCESSING);
        event.setClaimedAt(oldClaim);

        outbox.saveAndFlush(event);

        // Relay A dies. Recovery returns the row to PENDING and increments retry_count.
        assertThat(store.releaseStaleClaims()).isEqualTo(1);

        OutboxEvent afterRecovery = outbox.findById(event.getId()).orElseThrow();

        assertThat(afterRecovery.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(afterRecovery.getRetryCount()).isEqualTo(1);

        // Relay B now claims the same row.
        OutboxEvent newerClaim = outbox.findById(event.getId()).orElseThrow();
        newerClaim.setStatus(OutboxStatus.PROCESSING);
        newerClaim.setClaimedAt(Instant.now());
        outbox.saveAndFlush(newerClaim);

        // Relay A wakes up and reports its original successful publish.
        // Its retry_count is still 0, so it no longer owns this claim.
        store.markProcessed(List.of(event));

        OutboxEvent stillOwnedByNewRelay = outbox.findById(event.getId()).orElseThrow();

        assertThat(stillOwnedByNewRelay.getStatus())
                .isEqualTo(OutboxStatus.PROCESSING);
        assertThat(stillOwnedByNewRelay.getRetryCount())
                .isEqualTo(1);

        // Relay B can still complete its own claim.
        store.markProcessed(List.of(stillOwnedByNewRelay));

        OutboxEvent processed = outbox.findById(event.getId()).orElseThrow();

        assertThat(processed.getStatus())
                .isEqualTo(OutboxStatus.PROCESSED);
    }
}