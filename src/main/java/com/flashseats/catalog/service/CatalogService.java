package com.flashseats.catalog.service;

import com.flashseats.catalog.config.CatalogProperties;
import com.flashseats.catalog.dto.EventDetailResponse;
import com.flashseats.catalog.dto.EventListItemResponse;
import com.flashseats.catalog.dto.TierResponse;
import com.flashseats.catalog.exception.EventNotFoundException;
import com.flashseats.catalog.exception.PrewarmWindowClosedException;
import com.flashseats.catalog.exception.TierNotFoundException;
import com.flashseats.catalog.facade.EventSummary;
import com.flashseats.catalog.facade.TierSummary;
import com.flashseats.catalog.model.Event;
import com.flashseats.catalog.model.EventStatus;
import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.catalog.model.TicketTier;
import com.flashseats.catalog.repository.EventRepository;
import com.flashseats.catalog.repository.TicketTierRepository;
import com.flashseats.catalog.repository.TierInventoryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Event metadata, sale windows, and every movement of the inventory counter. */
@Service
public class CatalogService {

    /**
     * Returned by {@link #getRemaining} when no counter exists — a fault, never "sold out".
     * Mirrored on {@link com.flashseats.catalog.facade.CatalogFacade#COUNTER_UNAVAILABLE}, which is
     * the value other modules see.
     */
    public static final int COUNTER_UNAVAILABLE = -1;

    private static final Logger log = LoggerFactory.getLogger(CatalogService.class);

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final TierInventoryRepository inventory;
    private final CatalogProperties properties;
    private final Clock clock;

    public CatalogService(
            EventRepository events,
            TicketTierRepository tiers,
            TierInventoryRepository inventory,
            CatalogProperties properties,
            Clock clock) {
        this.events = events;
        this.tiers = tiers;
        this.inventory = inventory;
        this.properties = properties;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- browse

    @Transactional(readOnly = true)
    public List<EventListItemResponse> listEvents() {
        Instant now = clock.instant();
        return events.findByStatusOrderBySaleStartTimeAsc(EventStatus.PUBLISHED).stream()
                .map(event -> new EventListItemResponse(
                        event.getId(),
                        event.getTitle(),
                        event.getVenueName(),
                        event.getEventStartTime(),
                        event.getSaleStartTime(),
                        SaleWindows.statusOf(event, now)))
                .toList();
    }

    @Transactional(readOnly = true)
    public EventDetailResponse getEventDetail(long eventId) {
        Event event = requireEvent(eventId);
        Instant now = clock.instant();

        // One read for every counter, not one per tier: this is the landing page, and every visitor
        // loads it before the sale and reloads it while they wait.
        Map<Long, Integer> remainingByTier = remainingByTier(eventId);

        List<TierResponse> tierResponses = tiers.findByEventIdOrderByPriceCentsDesc(eventId).stream()
                .map(tier -> toTierResponse(tier, remainingByTier))
                .toList();

        return new EventDetailResponse(
                event.getId(),
                event.getTitle(),
                event.getDescription(),
                event.getVenueName(),
                event.getEventStartTime(),
                event.getSaleStartTime(),
                event.getSaleEndTime(),
                SaleWindows.statusOf(event, now),
                now,
                tierResponses);
    }

    // ----------------------------------------------------------- facade reads

    @Transactional(readOnly = true)
    public TierSummary getTierSummary(long eventId, long tierId) {
        Event event = requireEvent(eventId);
        TicketTier tier = tiers.findById(tierId)
                .filter(t -> t.getEventId().equals(eventId))
                .orElseThrow(() -> new TierNotFoundException(eventId, tierId));

        return new TierSummary(
                eventId,
                tier.getId(),
                tier.getTierName(),
                tier.getPriceCents(),
                tier.getCurrency(),
                tier.getMaxPerOrder(),
                event.getTitle(),
                event.getVenueName(),
                event.getEventStartTime(),
                event.getSaleEndTime(),
                SaleWindows.statusOf(event, clock.instant()));
    }

    @Transactional(readOnly = true)
    public EventSummary getEventSummary(long eventId) {
        Event event = requireEvent(eventId);
        return new EventSummary(
                event.getId(),
                event.getTitle(),
                event.getVenueName(),
                event.getEventStartTime(),
                event.getSaleStartTime(),
                event.getSaleEndTime(),
                SaleWindows.statusOf(event, clock.instant()));
    }

    @Transactional(readOnly = true)
    public EventWindowStatus getWindowStatus(long eventId) {
        return SaleWindows.statusOf(requireEvent(eventId), clock.instant());
    }

    @Transactional(readOnly = true)
    public List<Long> findOpenEventIds() {
        return events.findOpenEventIds(clock.instant());
    }

    /** @return remaining seats, or {@link #COUNTER_UNAVAILABLE} when no counter exists. */
    @Transactional(readOnly = true)
    public int getRemaining(long tierId) {
        return inventory.findRemaining(tierId).orElse(COUNTER_UNAVAILABLE);
    }

    /**
     * Total remaining across an event's tiers — the promoter's admission bound.
     *
     * <p><strong>"No counter" is never "zero"</strong> (ADR-035). A tier with no
     * {@code tier_inventory} row contributes nothing to a {@code SUM}, so an un-warmed event would
     * report {@code 0} remaining and be indistinguishable from a sold-out one. The caller would then
     * tell an entire waiting room the sale had ended because a row was missing — ADR-004's failure,
     * one module over. If any tier is missing its counter the whole answer is a fault.
     *
     * @return remaining seats across the event, or {@link #COUNTER_UNAVAILABLE} if any tier has no
     *     counter
     */
    @Transactional(readOnly = true)
    public int getRemainingForEvent(long eventId) {
        if (inventory.countTiersMissingInventory(eventId) > 0) {
            log.error("Event {} has tiers with no inventory counter; remaining is unreadable", eventId);
            return COUNTER_UNAVAILABLE;
        }
        return inventory.sumRemainingForEvent(eventId);
    }

    // ------------------------------------------------------ inventory movement

    /**
     * Atomically takes {@code quantity} seats from a tier.
     *
     * <p>{@link Propagation#MANDATORY} is the contract: this must run inside the caller's
     * transaction, never its own. {@code hold} decrements the counter and inserts the
     * {@code ticket_holds} row that justifies it in one transaction, so if the insert is rejected —
     * by the one-active-hold-per-session index, say — the decrement rolls back with it and the seats
     * are never lost (global standards §5).
     *
     * @return true when the seats are reserved; false when there were not enough
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean tryReserve(long tierId, int quantity) {
        return inventory.tryReserve(tierId, quantity, clock.instant()) == 1;
    }

    /**
     * Returns {@code quantity} seats to a tier.
     *
     * <p>Callers must have won the settle-once claim on the hold first — that claim, not this
     * statement, is what guarantees a hold's seats come back exactly once (ADR-019).
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void restore(long tierId, int quantity) {
        if (inventory.restore(tierId, quantity, clock.instant()) != 1) {
            // The counter row is missing entirely. Loud, because it means inventory accounting has
            // diverged and the tier's total is now wrong.
            log.error("Cannot restore {} seats to tier {}: no inventory row", quantity, tierId);
        }
    }

    // ---------------------------------------------------------------- pre-warm

    /**
     * Seeds inventory from {@code total_capacity}, and only while the sale is {@code UPCOMING}.
     *
     * <p>The window check is the whole point. Seeding an open sale from capacity would silently
     * resurrect every ticket already sold — the highest-severity defect the design review found
     * (ADR-004). Recovery during a live sale is a rebuild from the ledger, never a reseed.
     */
    @Transactional
    public int prewarm(long eventId) {
        Event event = requireEvent(eventId);
        if (SaleWindows.statusOf(event, clock.instant()) != EventWindowStatus.UPCOMING) {
            throw new PrewarmWindowClosedException(eventId);
        }
        int seeded = inventory.seedFromCapacity(eventId);
        log.info("Pre-warmed event {}: {} tier counters seeded", eventId, seeded);
        return seeded;
    }

    // ----------------------------------------------------------------- helpers

    private Event requireEvent(long eventId) {
        return events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
    }

    /** Tier id to remaining seats. A tier with no counter is absent, never zero (ADR-040). */
    private Map<Long, Integer> remainingByTier(long eventId) {
        Map<Long, Integer> remaining = new HashMap<>();
        for (Object[] row : inventory.findRemainingByEvent(eventId)) {
            remaining.put((Long) row[0], (Integer) row[1]);
        }
        return remaining;
    }

    /**
     * One tier as the public API exposes it.
     *
     * <p>A missing counter becomes {@link com.flashseats.catalog.model.AvailabilityLevel#UNKNOWN},
     * <strong>not</strong> {@code SOLD_OUT} (ADR-040). The previous code clamped
     * {@link #COUNTER_UNAVAILABLE} to zero with {@code Math.max}, which published "we cannot read
     * our own inventory" to every visitor as "this tier is gone" — ADR-004's failure mode reaching
     * the landing page. An un-warmed event announced itself sold out before its sale had even
     * started, and the client rendered that tier unselectable with no way to retry.
     */
    private TierResponse toTierResponse(TicketTier tier, Map<Long, Integer> remainingByTier) {
        int remaining = remainingByTier.getOrDefault(tier.getId(), COUNTER_UNAVAILABLE);
        if (remaining == COUNTER_UNAVAILABLE) {
            log.warn(
                    "Tier {} of event {} has no inventory counter; reporting availability as UNKNOWN",
                    tier.getId(),
                    tier.getEventId());
        }
        return new TierResponse(
                tier.getId(),
                tier.getTierName(),
                tier.getPriceCents(),
                tier.getCurrency(),
                tier.getMaxPerOrder(),
                AvailabilityBuckets.of(
                        remaining, tier.getTotalCapacity(), properties.getLimitedThresholdPercent()));
    }
}
