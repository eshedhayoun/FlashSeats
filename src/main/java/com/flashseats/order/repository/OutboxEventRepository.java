package com.flashseats.order.repository;

import com.flashseats.order.model.OutboxEvent;
import com.flashseats.order.model.OutboxStatus;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {

    /**
     * The oldest row in one status, for the fulfilment-lag gauge.
     *
     * <p><strong>By status, not by {@code <> PROCESSED}</strong>, and that is the whole point. The
     * two indexes this table has are partial — `(created_at) WHERE status = 'PENDING'` and
     * `(claimed_at) WHERE status = 'PROCESSING'` (`V3`) — and PostgreSQL cannot prove that
     * {@code status <> 'PROCESSED'} implies either predicate. Asked that way the gauge sequentially
     * scanned the whole table, <em>including</em> every processed row not yet purged, every ten
     * seconds, on every replica: an observer putting load on the pool it exists to observe, which is
     * ADR-051's trap reached through a metric.
     *
     * <p>Two bounded reads instead of one unbounded one. {@code PENDING} is served by its index
     * directly; {@code PROCESSING} holds only in-flight claims — a batch per replica — so scanning
     * its partial index costs nothing. Deliberately **no new index**: this table is written on every
     * checkout, and `V12` has already had to drop indexes nothing queried.
     */
    @Query("SELECT MIN(e.createdAt) FROM OutboxEvent e WHERE e.status = :status")
    Optional<Instant> oldestCreatedAtWithStatus(@Param("status") OutboxStatus status);

    /**
     * The most recent message published for an order, whatever became of it.
     *
     * <p>This is what makes an operator resend possible at all. The payload is a complete,
     * self-contained snapshot of everything needed to render a ticket (ADR-015), and this table is
     * the only place it durably lives — {@code notification_logs} records that a delivery was
     * attempted, not what was in it.
     *
     * <p>Bounded by {@code flashseats.outbox.purge-after-days}: past that the nightly purge has
     * removed the row and the message cannot be reconstructed. The resend endpoint says so rather
     * than inventing one.
     */
    Optional<OutboxEvent> findFirstByAggregateIdAndEventTypeOrderByCreatedAtDesc(
            String aggregateId, String eventType);

    /**
     * Takes a batch of pending events for this replica alone.
     *
     * <p>{@code FOR UPDATE SKIP LOCKED} — the pessimistic write lock plus a lock timeout of
     * {@code -2}, which is how Hibernate spells {@code SKIP LOCKED} — is what makes this safe to run
     * everywhere at once. Without it, three replicas polling {@code WHERE status = 'PENDING'} would
     * each publish every event, and every buyer would get three tickets (ADR-009).
     *
     * <p>Rows another replica already holds are skipped rather than waited for, so relays never queue
     * behind each other.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("""
            SELECT e FROM OutboxEvent e
             WHERE e.status = com.flashseats.order.model.OutboxStatus.PENDING
             ORDER BY e.createdAt
            """)
    List<OutboxEvent> claimPending(Limit limit);

    /**
     * Marks one published claim done.
     *
     * <p>{@code status = PROCESSING} alone is not enough. A relay may publish a message, die, and
     * have its claim returned to {@code PENDING}; another relay can then claim the same row before
     * the first one wakes up. {@code retryCount} is the claim generation: stale-claim recovery
     * increments it, so an old relay cannot mark the newer relay's claim as processed (ADR-009).
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE OutboxEvent e
               SET e.status = com.flashseats.order.model.OutboxStatus.PROCESSED,
                   e.processedAt = :now
             WHERE e.id = :id
               AND e.status = com.flashseats.order.model.OutboxStatus.PROCESSING
               AND e.retryCount = :retryCount
            """)
    int markProcessed(
            @Param("id") UUID id,
            @Param("retryCount") int retryCount,
            @Param("now") Instant now);

    /**
     * Returns rows stranded in {@code PROCESSING} to {@code PENDING}.
     *
     * <p>Covers the crash window between claiming a batch and publishing it. Re-publishing is
     * at-least-once, which the consumer's unique constraint absorbs — losing the message would not be
     * recoverable at all.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE OutboxEvent e
               SET e.status = com.flashseats.order.model.OutboxStatus.PENDING,
                   e.claimedAt = null,
                   e.retryCount = e.retryCount + 1
             WHERE e.status = com.flashseats.order.model.OutboxStatus.PROCESSING
               AND e.claimedAt < :staleBefore
            """)
    int releaseStaleClaims(@Param("staleBefore") Instant staleBefore);

    @Modifying(flushAutomatically = true)
    @Query("""
            DELETE FROM OutboxEvent e
             WHERE e.status = com.flashseats.order.model.OutboxStatus.PROCESSED
               AND e.processedAt < :before
            """)
    int purgeProcessedBefore(@Param("before") Instant before);
}
