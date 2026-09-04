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
import java.util.List;
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

        List<TierResponse> tierResponses = tiers.findByEventIdOrderByPriceCentsDesc(eventId).stream()
                .map(this::toTierResponse)
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

    /** Total remaining across an event's tiers — the promoter's admission bound. */
    @Transactional(readOnly = true)
    public int getRemainingForEvent(long eventId) {
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

    private TierResponse toTierResponse(TicketTier tier) {
        int remaining = getRemaining(tier.getId());
        return new TierResponse(
                tier.getId(),
                tier.getTierName(),
                tier.getPriceCents(),
                tier.getCurrency(),
                tier.getMaxPerOrder(),
                AvailabilityBuckets.of(
                        Math.max(remaining, 0),
                        tier.getTotalCapacity(),
                        properties.getLimitedThresholdPercent()));
    }
}
