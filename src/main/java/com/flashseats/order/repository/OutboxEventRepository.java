package com.flashseats.order.repository;

import com.flashseats.order.model.OutboxEvent;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
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

    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE OutboxEvent e
               SET e.status = com.flashseats.order.model.OutboxStatus.PROCESSED,
                   e.processedAt = :now
             WHERE e.id IN :ids
            """)
    int markProcessed(@Param("ids") Collection<UUID> ids, @Param("now") Instant now);

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
