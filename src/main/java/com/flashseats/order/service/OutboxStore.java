package com.flashseats.order.service;

import com.flashseats.order.config.OutboxProperties;
import com.flashseats.order.model.OutboxEvent;
import com.flashseats.order.model.OutboxStatus;
import com.flashseats.order.repository.OutboxEventRepository;
import java.time.Clock;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The <strong>short</strong> transactions of the outbox relay.
 *
 * <p>Three of them, never one (ADR-023): claim and commit · publish outside any transaction · mark
 * processed. Publishing inside the claim would hold {@code FOR UPDATE} row locks across a network
 * round trip to the broker.
 *
 * <p>On its own bean so the boundaries are real proxies rather than self-invocations that silently
 * run without a transaction.
 */
@Component
public class OutboxStore {

    private final OutboxEventRepository outbox;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxStore(OutboxEventRepository outbox, OutboxProperties properties, Clock clock) {
        this.outbox = outbox;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Takes a batch for this replica alone and commits immediately.
     *
     * <p>{@code SKIP LOCKED} is what makes the relay safe to run everywhere at once: rows another
     * replica already holds are passed over rather than waited for, so no event is ever published
     * twice and no relay queues behind another.
     */
    @Transactional
    public List<OutboxEvent> claimBatch() {
        List<OutboxEvent> batch = outbox.claimPending(Limit.of(properties.getBatchSize()));
        batch.forEach(event -> {
            event.setStatus(OutboxStatus.PROCESSING);
            event.setClaimedAt(clock.instant());
        });
        return batch;
    }

    @Transactional
    public void markProcessed(Collection<UUID> ids) {
        outbox.markProcessed(ids, clock.instant());
    }

    /** Recovers rows whose relay died between claiming and publishing. */
    @Transactional
    public int releaseStaleClaims() {
        return outbox.releaseStaleClaims(
                clock.instant().minus(Duration.ofSeconds(properties.getStaleClaimSeconds())));
    }

    /** Keeps the table from growing forever. */
    @Transactional
    public int purgeProcessed() {
        return outbox.purgeProcessedBefore(
                clock.instant().minus(Duration.ofDays(properties.getPurgeAfterDays())));
    }
}
