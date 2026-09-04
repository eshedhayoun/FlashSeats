package com.flashseats.catalog.repository;

import com.flashseats.catalog.model.TierInventory;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Access to the live remaining counts. */
public interface TierInventoryRepository extends JpaRepository<TierInventory, Long> {

    /**
     * <strong>The no-overbooking guarantee, in one statement.</strong>
     *
     * <p>The precondition lives in the {@code WHERE} clause, so PostgreSQL takes the row lock and
     * two concurrent callers are serialised by the database rather than by application logic. The
     * loser's {@code remaining >= :quantity} is simply false by the time it runs, and it affects
     * zero rows.
     *
     * <p>A {@code SELECT} followed by an {@code UPDATE} would be a race no amount of care in Java
     * could close. {@code CHECK (remaining >= 0)} on the column backstops even this.
     *
     * @return 1 when the seats are reserved, 0 when there were not enough
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE TierInventory i
               SET i.remaining = i.remaining - :quantity, i.updatedAt = :now
             WHERE i.tierId = :tierId
               AND i.remaining >= :quantity
            """)
    int tryReserve(
            @Param("tierId") long tierId, @Param("quantity") int quantity, @Param("now") Instant now);

    /**
     * Returns seats to the pool. Unconditional by design: the caller has already won the
     * settle-once claim on {@code ticket_holds}, and that claim — not this statement — is what
     * guarantees a hold's seats are returned exactly once (ADR-019).
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE TierInventory i
               SET i.remaining = i.remaining + :quantity, i.updatedAt = :now
             WHERE i.tierId = :tierId
            """)
    int restore(
            @Param("tierId") long tierId, @Param("quantity") int quantity, @Param("now") Instant now);

    /**
     * Seeds every tier of an event from its {@code total_capacity}, skipping tiers that already have
     * a counter — the SQL equivalent of {@code SETNX}, so a repeated pre-warm is a no-op and two
     * replicas racing cannot conflict.
     *
     * <p>The caller <strong>must</strong> have checked that the window is {@code UPCOMING} first.
     * Running this on an open sale would overwrite nothing but would create counters for tiers whose
     * seats are already sold (ADR-004).
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    """
                    INSERT INTO tier_inventory (tier_id, event_id, remaining, updated_at)
                    SELECT t.id, t.event_id, t.total_capacity, now()
                      FROM ticket_tiers t
                     WHERE t.event_id = :eventId
                    ON CONFLICT (tier_id) DO NOTHING
                    """,
            nativeQuery = true)
    int seedFromCapacity(@Param("eventId") long eventId);

    @Query("SELECT i.remaining FROM TierInventory i WHERE i.tierId = :tierId")
    Optional<Integer> findRemaining(@Param("tierId") long tierId);

    @Query("SELECT COALESCE(SUM(i.remaining), 0) FROM TierInventory i WHERE i.eventId = :eventId")
    int sumRemainingForEvent(@Param("eventId") long eventId);
}
