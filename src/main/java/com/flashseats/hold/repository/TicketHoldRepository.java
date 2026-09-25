package com.flashseats.hold.repository;

import com.flashseats.hold.model.HoldStatus;
import com.flashseats.hold.model.SettleReason;
import com.flashseats.hold.model.TicketHold;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketHoldRepository extends JpaRepository<TicketHold, Long> {

    Optional<TicketHold> findByHoldToken(String holdToken);

    Optional<TicketHold> findByUserSessionIdAndEventIdAndStatus(
            String userSessionId, long eventId, HoldStatus status);

    /**
     * <strong>The settle-once claim.</strong> Every ending of every hold runs this one statement.
     * {@code AND status = 'ACTIVE'} is the whole concurrency design: on any number of replicas exactly
     * one caller sees rowcount 1, and only that caller returns the seats. It is in SQL, not Redis, so a
     * failed order commit rolls it back (ADR-019).
     *
     * <p>No {@code clearAutomatically}: this runs inside the order transaction, and clearing would detach
     * the {@code Order} being updated, silently discarding its changes.
     *
     * @return 1 if this caller won the claim, 0 if the hold was already settled
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE TicketHold h
               SET h.status = :status,
                   h.settledAt = :now,
                   h.settleReason = :reason,
                   h.updatedAt = :now
             WHERE h.holdToken = :holdToken
               AND h.status = com.flashseats.hold.model.HoldStatus.ACTIVE
            """)
    int settle(
            @Param("holdToken") String holdToken,
            @Param("status") HoldStatus status,
            @Param("reason") SettleReason reason,
            @Param("now") Instant now);

    /**
     * Grants the single grace extension.
     *
     * <p>{@code AND extendedCount = 0} puts the "once per hold" ceiling in the statement itself, so
     * it cannot be farmed by concurrent requests. A per-attempt extension would allow
     * 300 + 3×120 = 660 s and make deliberate declines a cheap way to squat on inventory (ADR-030).
     *
     * @return 1 if the extension was granted, 0 if the hold was already extended or already settled
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE TicketHold h
               SET h.expiresAt = :newExpiry,
                   h.extendedCount = h.extendedCount + 1,
                   h.updatedAt = :now
             WHERE h.holdToken = :holdToken
               AND h.status = com.flashseats.hold.model.HoldStatus.ACTIVE
               AND h.extendedCount = 0
            """)
    int extendOnce(
            @Param("holdToken") String holdToken,
            @Param("newExpiry") Instant newExpiry,
            @Param("now") Instant now);

    /** Feeds the reconciliation sweeper. Backed by a partial index on {@code status = 'ACTIVE'}. */
    @Query("""
            SELECT h FROM TicketHold h
             WHERE h.status = com.flashseats.hold.model.HoldStatus.ACTIVE
               AND h.expiresAt < :now
            """)
    List<TicketHold> findExpired(@Param("now") Instant now, Limit limit);

    /** Held seats per tier, for the stock invariant check. */
    @Query("""
            SELECT COALESCE(SUM(h.quantity), 0) FROM TicketHold h
             WHERE h.tierId = :tierId
               AND h.status = com.flashseats.hold.model.HoldStatus.ACTIVE
            """)
    int sumActiveQuantityForTier(@Param("tierId") long tierId);
}
