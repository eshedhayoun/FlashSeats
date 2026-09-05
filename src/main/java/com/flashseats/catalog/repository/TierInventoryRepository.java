package com.flashseats.catalog.repository;

import com.flashseats.catalog.model.TierInventory;
import java.time.Instant;
import java.util.List;
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

    /**
     * Every counter this event has, in one round trip.
     *
     * <p>The browse read renders one badge per tier, and doing that with a lookup per tier put an
     * N+1 on the single hottest endpoint in the system — the landing page, which every visitor loads
     * before the sale opens and reloads while they wait.
     *
     * <p>A tier with no counter is simply <em>absent</em> from the result, which is the point: the
     * caller sees the gap rather than a zero, and answers {@link
     * com.flashseats.catalog.model.AvailabilityLevel#UNKNOWN} (ADR-040).
     */
    @Query("SELECT i.tierId, i.remaining FROM TierInventory i WHERE i.eventId = :eventId")
    List<Object[]> findRemainingByEvent(@Param("eventId") long eventId);

    @Query("SELECT COALESCE(SUM(i.remaining), 0) FROM TierInventory i WHERE i.eventId = :eventId")
    int sumRemainingForEvent(@Param("eventId") long eventId);

    /**
     * How many of an event's tiers have no counter row at all.
     *
     * <p>This exists because {@code SUM} cannot express the difference between "nothing left" and
     * "nothing known" — {@code COALESCE(SUM(...), 0)} answers {@code 0} for both. Reading that
     * {@code 0} as "sold out" is ADR-004's failure mode, and it reached the waiting room: an event
     * that was never pre-warmed made the promotion worker declare the sale exhausted and drain
     * everybody out of the line (ADR-035).
     *
     * @return 0 when every tier has a counter and the sum is therefore meaningful
     */
    @Query("""
            SELECT COUNT(t) FROM TicketTier t
             WHERE t.eventId = :eventId
               AND NOT EXISTS (SELECT 1 FROM TierInventory i WHERE i.tierId = t.id)
            """)
    int countTiersMissingInventory(@Param("eventId") long eventId);
}
