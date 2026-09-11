package com.flashseats.catalog.repository;

import com.flashseats.catalog.model.TierInventory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * The ledger's copy of each tier's remaining count.
 *
 * <p><strong>Not the live counter.</strong> Redis holds that, and only
 * {@link StockCounterRepository} moves it. This table is written by pre-warm and by rebuild, so
 * between the two it is a last-known-good record rather than a current one — reading it to answer a
 * buyer would report a number that stopped being true at the first sale.
 *
 * <p>It is kept because {@code CHECK (remaining >= 0)} is the database's own statement that
 * overbooking may not be persisted, and because a rebuild needs somewhere durable to record what it
 * concluded.
 */
public interface TierInventoryRepository extends JpaRepository<TierInventory, Long> {

    /**
     * Seeds every tier of an event from its {@code total_capacity}, skipping tiers that already have
     * a row — the SQL equivalent of {@code SETNX}, so a repeated pre-warm is a no-op and two
     * replicas racing cannot conflict.
     *
     * <p>The caller <strong>must</strong> have checked that the window is {@code UPCOMING} first.
     * Running this on an open sale would create rows for tiers whose seats are already sold
     * (ADR-004).
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

    /**
     * Writes one tier's last-known-good count, creating the row if the tier never had one.
     *
     * <p>Unlike {@link #seedFromCapacity} this <em>overwrites</em>, so it is the rebuild's statement
     * and nothing else's: the value must already have been derived from the ledger under the rebuild
     * lock. Reaching for it with {@code total_capacity} would be ADR-004's defect with extra steps.
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    """
                    INSERT INTO tier_inventory (tier_id, event_id, remaining, updated_at)
                    VALUES (:tierId, :eventId, :remaining, now())
                    ON CONFLICT (tier_id)
                    DO UPDATE SET remaining = EXCLUDED.remaining, updated_at = now()
                    """,
            nativeQuery = true)
    int upsertRemaining(
            @Param("eventId") long eventId,
            @Param("tierId") long tierId,
            @Param("remaining") int remaining);
}
