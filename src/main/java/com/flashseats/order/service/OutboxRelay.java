package com.flashseats.order.service;

import com.flashseats.order.config.OutboxProperties;
import com.flashseats.order.model.OutboxEvent;
import java.util.List;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Drains the outbox: claim, publish, mark.
 *
 * <p>Runs on every replica, which is safe because the claim uses {@code FOR UPDATE SKIP LOCKED} —
 * idempotent by claim, the first of the two shapes a scheduled job is allowed to take (global
 * standards §7).
 *
 * <p>A crash between publish and mark re-publishes on a later sweep. That is at-least-once, and it
 * is the right trade: the consumer's unique constraint absorbs a duplicate, whereas a lost ticket
 * email has no recovery path at all.
 */
@Slf4j
@Component
public class OutboxRelay {

    private final OutboxStore store;
    private final OutboxPublisher publisher;
    private final int staleClaimSeconds;

    public OutboxRelay(OutboxStore store, OutboxPublisher publisher, OutboxProperties properties) {
        this.store = store;
        this.publisher = publisher;
        this.staleClaimSeconds = properties.getStaleClaimSeconds();
    }

    @Scheduled(
            fixedDelayString = "${flashseats.outbox.poll-interval-ms}",
            initialDelayString = "${flashseats.outbox.poll-interval-ms}")
    public void relay() {
        List<OutboxEvent> batch = store.claimBatch(); // tx1 — committed before we publish
        if (batch.isEmpty()) {
            return;
        }

        // No transaction open. The publisher reports what the transport durably accepted, which may
        // be fewer rows than were handed to it — a broker that nacks, routes nowhere, or does not
        // answer in time. Anything absent stays PROCESSING and recoverStaleClaims returns it to
        // PENDING, so the outcome of a bad batch is a retry rather than a lost ticket.
        List<UUID> published = publisher.publish(batch);

        if (!published.isEmpty()) {
            store.markProcessed(published); // tx2
        }

        if (published.size() < batch.size()) {
            log.warn(
                    "{} of {} outbox event(s) were not confirmed; they stay PROCESSING and will be"
                            + " retried after {}s",
                    batch.size() - published.size(),
                    batch.size(),
                    staleClaimSeconds);
        }
    }

    /** Returns orphaned claims to the queue. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    public void recoverStaleClaims() {
        int recovered = store.releaseStaleClaims();
        if (recovered > 0) {
            log.warn("Returned {} stranded outbox event(s) to PENDING", recovered);
        }
    }

    /** Nightly cleanup. Idempotent, so running on all replicas is harmless. */
    @Scheduled(cron = "0 15 3 * * *")
    public void purge() {
        int purged = store.purgeProcessed();
        if (purged > 0) {
            log.info("Purged {} processed outbox event(s)", purged);
        }
    }
}
