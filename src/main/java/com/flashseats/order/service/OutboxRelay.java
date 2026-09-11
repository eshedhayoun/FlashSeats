package com.flashseats.order.service;

import com.flashseats.order.model.OutboxEvent;
import java.util.ArrayList;
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

    public OutboxRelay(OutboxStore store, OutboxPublisher publisher) {
        this.store = store;
        this.publisher = publisher;
    }

    @Scheduled(
            fixedDelayString = "${flashseats.outbox.poll-interval-ms}",
            initialDelayString = "${flashseats.outbox.poll-interval-ms}")
    public void relay() {
        List<OutboxEvent> batch = store.claimBatch(); // tx1 — committed before we publish
        if (batch.isEmpty()) {
            return;
        }

        List<UUID> published = new ArrayList<>(batch.size());
        for (OutboxEvent event : batch) {
            try {
                publisher.publish(event); // no transaction open
                published.add(event.getId());
            } catch (RuntimeException failed) {
                // Left PROCESSING on purpose: the stale-claim sweep returns it to PENDING and it is
                // retried, rather than being silently dropped here.
                log.error("Failed to publish outbox event {}", event.getId(), failed);
            }
        }

        if (!published.isEmpty()) {
            store.markProcessed(published); // tx2
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
