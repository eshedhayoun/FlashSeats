package com.flashseats.catalog.service;

import com.flashseats.catalog.config.CatalogProperties;
import com.flashseats.catalog.dto.EventDetailResponse;
import com.flashseats.catalog.dto.EventListItemResponse;
import com.flashseats.catalog.dto.TierResponse;
import com.flashseats.catalog.exception.CatalogErrors;
import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.catalog.facade.EventSummary;
import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.catalog.facade.ReserveResult;
import com.flashseats.catalog.facade.TierAvailability;
import com.flashseats.catalog.facade.TierSummary;
import com.flashseats.catalog.model.AvailabilityLevel;
import com.flashseats.catalog.model.Event;
import com.flashseats.catalog.model.EventStatus;
import com.flashseats.catalog.repository.EventRepository;
import com.flashseats.catalog.repository.StockCounterRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Event metadata, sale windows, and every movement of the inventory counter. This class implements
 * {@link CatalogFacade} (ADR-057); other modules see only the interface.
 */
@Slf4j
@Service
public class CatalogService implements CatalogFacade {

    private final EventRepository events;
    private final StockCounterRepository stock;
    private final StockEpoch epoch;
    private final CatalogMetadata metadata;
    private final ApplicationEventPublisher publisher;
    private final CatalogProperties properties;
    private final Clock clock;

    public CatalogService(
            EventRepository events,
            StockCounterRepository stock,
            StockEpoch epoch,
            CatalogMetadata metadata,
            ApplicationEventPublisher publisher,
            CatalogProperties properties,
            Clock clock) {
        this.events = events;
        this.stock = stock;
        this.epoch = epoch;
        this.metadata = metadata;
        this.publisher = publisher;
        this.properties = properties;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- browse

    public List<EventListItemResponse> listEvents() {
        Instant now = clock.instant();
        return metadata.selectableEvents().stream()
                .filter(event -> event.status() == EventStatus.PUBLISHED)
                .map(event -> new EventListItemResponse(
                        event.id(),
                        event.title(),
                        event.venueName(),
                        event.eventStartTime(),
                        event.saleStartTime(),
                        event.windowStatus(now)))
                .toList();
    }

    /**
     * The landing page.
     *
     * <p>Deliberately not wrapped in one transaction: the counters live in Redis now, and holding a
     * pooled database connection open across that round trip — on the single hottest endpoint in the
     * system — would spend the connection pool on a read that needs no consistency between the
     * event's metadata and its inventory. With the metadata cache warm it takes no connection at all
     * (ADR-051).
     */
    public EventDetailResponse getEventDetail(long eventId) {
        EventRow event = metadata.event(eventId);
        Instant now = clock.instant();
        List<TierRow> eventTiers = metadata.tiers(eventId);

        // One round trip for every counter, not one per tier: every visitor loads this before the
        // sale and reloads it while they wait.
        Map<Long, Integer> remainingByTier =
                stock.readAll(eventId, eventTiers.stream().map(TierRow::id).toList());

        List<TierResponse> tierResponses = eventTiers.stream()
                .map(tier -> toTierResponse(tier, remainingByTier))
                .toList();

        return new EventDetailResponse(
                event.id(),
                event.title(),
                event.description(),
                event.venueName(),
                event.eventStartTime(),
                event.saleStartTime(),
                event.saleEndTime(),
                event.windowStatus(now),
                now,
                tierResponses);
    }

    // ----------------------------------------------------------- facade reads

    @Override
    public TierSummary getTierSummary(long eventId, long tierId) {
        EventRow event = metadata.event(eventId);
        TierRow tier = metadata.tiers(eventId).stream()
                .filter(candidate -> candidate.id() == tierId)
                .findFirst()
                .orElseThrow(() -> CatalogErrors.tierNotFound(eventId, tierId));

        return new TierSummary(
                eventId,
                tier.id(),
                tier.tierName(),
                tier.priceCents(),
                tier.currency(),
                tier.maxPerOrder(),
                event.title(),
                event.venueName(),
                event.eventStartTime(),
                event.saleEndTime(),
                event.windowStatus(clock.instant()));
    }

    @Override
    public EventSummary getEventSummary(long eventId) {
        EventRow event = metadata.event(eventId);
        return new EventSummary(
                event.id(),
                event.title(),
                event.venueName(),
                event.eventStartTime(),
                event.saleStartTime(),
                event.saleEndTime(),
                event.windowStatus(clock.instant()));
    }

    @Override
    public EventWindowStatus getWindowStatus(long eventId) {
        return metadata.event(eventId).windowStatus(clock.instant());
    }

    /**
     * Ids of events inside their sale window now, which the promotion worker ticks over. Served from the
     * metadata cache as a correctness choice, so the promoter never queues for a connection behind the
     * buyers it admits (ADR-051). The window is still compared against the live clock.
     */
    @Override
    public List<Long> findOpenEventIds() {
        Instant now = clock.instant();
        return metadata.selectableEvents().stream()
                .filter(event -> event.status() == EventStatus.PUBLISHED)
                .filter(event -> isInsideWindow(event, now))
                .map(EventRow::id)
                .toList();
    }

    /** Open <em>or paused</em> — what an operator is still answerable for. See the repository. */
    @Override
    public List<Long> findManagedEventIds() {
        Instant now = clock.instant();
        return metadata.selectableEvents().stream()
                .filter(event -> isInsideWindow(event, now))
                .map(EventRow::id)
                .toList();
    }

    /**
     * The window predicate both id lists share, matching the repository's to the boundary: sale start
     * inclusive, sale end exclusive. Two implementations that rounded an edge differently would put an
     * event in the drift gauge's set and not the promoter's.
     */
    private static boolean isInsideWindow(EventRow event, Instant now) {
        return !now.isBefore(event.saleStartTime()) && now.isBefore(event.saleEndTime());
    }

    /**
     * Halts a live sale, or resumes it. Nothing is torn down: the waiting room, passes, admissions and
     * stock stay as they are, so resuming returns every buyer to where they were (ADR-035's reasoning).
     * Idempotent. The cache eviction is published {@code AFTER_COMMIT}, so a concurrent reader cannot
     * re-cache the row before this change lands.
     *
     * @throws com.flashseats.shared.error.FlashSeatsException {@code SALE_PAUSED} if the event is
     *     {@code DRAFT} or {@code CANCELLED}
     */
    @Transactional
    public EventStatus setPaused(long eventId, boolean paused) {
        Event event = requireEvent(eventId);
        EventStatus current = event.getStatus();

        if (current != EventStatus.PUBLISHED && current != EventStatus.PAUSED) {
            throw CatalogErrors.eventNotPausable(eventId, current);
        }

        EventStatus target = paused ? EventStatus.PAUSED : EventStatus.PUBLISHED;
        if (current != target) {
            event.setStatus(target);
            log.warn("Event {} {} by an operator", eventId, paused ? "PAUSED" : "resumed");
        }
        publisher.publishEvent(new EventMetadataChanged(eventId));
        return target;
    }

    /**
     * Total remaining across an event's tiers: the promoter's admission bound. <strong>"No counter" is
     * never "zero"</strong> (ADR-035). One missing counter makes the whole answer a fault, and the tier
     * list comes from {@code ticket_tiers}, not from the counters, so an evicted event cannot look
     * empty.
     *
     * @return remaining seats across the event, or {@link #COUNTER_UNAVAILABLE} if any tier has no
     *     counter
     */
    @Override
    public int getRemainingForEvent(long eventId) {
        List<Long> tierIds = tierIds(eventId);
        Map<Long, Integer> counters = stock.readAll(eventId, tierIds);

        if (tierIds.isEmpty()) {
            // An event with NO TIERS is unknowable inventory, not zero inventory. Summing an empty
            // list answers 0, which the promotion worker reads as sold out -- and it then marks the
            // event exhausted permanently, because the marker clears only when remaining > 0 and a
            // tier-less event never reports that. ADR-035's trap, surviving in the one method written
            // to kill it: the guard below asks "is any counter missing?" and not "is there anything
            // to count?". Reachable whenever an event exists before its tiers do, which is every
            // seeder, every fixture and any future create-event endpoint.
            log.error("Event {} has no tiers; remaining is unknowable, not zero", eventId);
            return COUNTER_UNAVAILABLE;
        }
        if (counters.size() < tierIds.size()) {
            log.error("Event {} has tiers with no inventory counter; remaining is unreadable", eventId);
            return COUNTER_UNAVAILABLE;
        }
        return counters.values().stream().mapToInt(Integer::intValue).sum();
    }

    /**
     * The same bucket view as the landing page, packaged for the waiting-room stream.
     *
     * <p>The queue module deliberately receives only buckets, not counts. Exact live inventory
     * would become a public feed the moment it crossed the SSE boundary (ADR-027).
     *
     * <p>Buckets are computed directly here rather than through {@link #toTierResponse}: this runs
     * on the broadcaster's sweep, on every replica, so borrowing the landing page's per-tier WARN
     * would turn one unreadable counter into a log flood during the incident that caused it.
     */
    @Override
    public List<TierAvailability> getTierAvailability(long eventId) {
        List<TierRow> eventTiers = metadata.tiers(eventId);
        Map<Long, Integer> remainingByTier =
                stock.readAll(eventId, eventTiers.stream().map(TierRow::id).toList());

        return eventTiers.stream()
                .map(tier -> new TierAvailability(tier.id(), bucketOf(tier, remainingByTier).name()))
                .toList();
    }

    // ------------------------------------------------------ inventory movement

    /**
     * Atomically takes seats: one Lua script decides <em>readable</em> and <em>sufficient</em> in the
     * same step (ADR-004). Must not be called inside a transaction, because Redis cannot roll back
     * (ADR-023); the caller writes its hold next and {@link #restore}s on a definite rejection.
     */
    @Override
    public ReserveResult tryReserve(long eventId, long tierId, int quantity) {
        if (!epoch.isTrusted(eventId)) {
            // Redis is not the instance that vouched for this event. Its counter may read high by
            // whatever the AOF lost, and selling from it would oversell (ADR-004).
            log.error(
                    "Refusing to reserve from event {}: counters not vouched for by the running"
                            + " Redis. Rebuild this event to resume selling",
                    eventId);
            return ReserveResult.COUNTER_MISSING;
        }

        ReserveResult result = stock.reserve(eventId, tierId, quantity);
        if (result == ReserveResult.COUNTER_MISSING) {
            log.error(
                    "No inventory counter for tier {} of event {}; reserve refused. Rebuild required"
                            + " (ADR-004)",
                    tierId,
                    eventId);
        }
        return result;
    }

    /**
     * Returns seats to a tier. Only after winning the settle-once claim, and only once it has committed
     * (ADR-019, ADR-046). A missing counter stays missing: creating one would conjure inventory
     * (ADR-004), so the drift gauge and a rebuild handle it.
     */
    @Override
    public void restore(long eventId, long tierId, int quantity) {
        if (!stock.restore(eventId, tierId, quantity)) {
            log.error(
                    "Cannot restore {} seats to tier {} of event {}: no counter. Those seats are"
                            + " invisible until a rebuild runs",
                    quantity,
                    tierId,
                    eventId);
        }
    }

    // ---------------------------------------------------------------- pre-warm

    /**
     * Seeds inventory from {@code total_capacity}, only while the sale is {@code UPCOMING}: seeding an
     * open sale would resurrect every sold ticket (ADR-004). The tier list is read uncached, so no tier
     * is left without a counter.
     *
     * @return how many tier counters this call created; a repeat returns 0 and changes nothing
     */
    public int prewarm(long eventId) {
        EventRow event = EventRow.of(requireEvent(eventId));
        if (event.windowStatus(clock.instant()) != EventWindowStatus.UPCOMING) {
            throw CatalogErrors.prewarmWindowClosed(eventId);
        }

        int seeded = 0;
        for (TierRow tier : metadata.tiersUncached(eventId)) {
            if (stock.seedIfAbsent(eventId, tier.id(), tier.totalCapacity())) {
                seeded++;
            }
        }
        // Sound by construction: nothing has been sold from an UPCOMING sale, so these counters owe
        // nothing to whatever a Redis restart may have lost.
        epoch.vouchFor(eventId);

        log.info("Pre-warmed event {}: {} tier counters seeded", eventId, seeded);
        return seeded;
    }

    // ----------------------------------------------------------------- rebuild

    /**
     * Tier id to the capacity it was created with — what a rebuild counts down from.
     *
     * <p>Uncached for the same reason as {@link #prewarm}: these numbers are written onto the live
     * counters, and a list that is merely probably right produces a counter that is definitely
     * wrong (ADR-046).
     */
    @Transactional(readOnly = true)
    @Override
    public Map<Long, Integer> getTierCapacities(long eventId) {
        Map<Long, Integer> capacities = new HashMap<>();
        for (TierRow tier : metadata.tiersUncached(eventId)) {
            capacities.put(tier.id(), tier.totalCapacity());
        }
        return capacities;
    }

    /**
     * The live counters, straight from Redis. A tier with no counter is absent, never zero.
     *
     * <p>Deliberately not {@code @Transactional}: the only SQL here is the tier-id lookup, and
     * holding a pooled connection across a Redis round trip buys nothing.
     */
    @Override
    public Map<Long, Integer> getLiveCounters(long eventId) {
        return stock.readAll(eventId, tierIds(eventId));
    }

    /**
     * Writes ledger-derived counts over the live counters.
     *
     * <p>Pure Redis, and there is nowhere else for it to be: the counter has no copy in PostgreSQL
     * to keep in step. The numbers came from the ledger — {@code ticket_tiers} minus what
     * {@code order_items} sold and {@code ticket_holds} is holding — and go straight onto the keys.
     */
    @Override
    public void applyRebuild(long eventId, Map<Long, Integer> remainingByTier) {
        Map<Long, Integer> before = stock.readAll(eventId, List.copyOf(remainingByTier.keySet()));

        remainingByTier.forEach((tierId, remaining) -> stock.overwrite(eventId, tierId, remaining));
        epoch.vouchFor(eventId);

        // WARN, not INFO: reaching this method at all means a counter was lost or had drifted, and
        // the two maps are the only record of how far. A tier absent from `before` had no counter.
        log.warn("Rebuilt stock for event {}: {} -> {}", eventId, before, remainingByTier);
    }

    // ----------------------------------------------------------------- helpers

    private List<Long> tierIds(long eventId) {
        return metadata.tiers(eventId).stream().map(TierRow::id).toList();
    }

    private Event requireEvent(long eventId) {
        return events.findById(eventId).orElseThrow(() -> CatalogErrors.eventNotFound(eventId));
    }

    /**
     * One tier as the public API exposes it. A missing counter becomes {@code UNKNOWN}, never
     * {@code SOLD_OUT} (ADR-040).
     */
    private TierResponse toTierResponse(TierRow tier, Map<Long, Integer> remainingByTier) {
        if (remainingByTier.getOrDefault(tier.id(), COUNTER_UNAVAILABLE) == COUNTER_UNAVAILABLE) {
            log.warn(
                    "Tier {} of event {} has no inventory counter; reporting availability as UNKNOWN",
                    tier.id(),
                    tier.eventId());
        }
        return new TierResponse(
                tier.id(),
                tier.tierName(),
                tier.priceCents(),
                tier.currency(),
                tier.maxPerOrder(),
                bucketOf(tier, remainingByTier));
    }

    private AvailabilityLevel bucketOf(TierRow tier, Map<Long, Integer> remainingByTier) {
        return AvailabilityLevel.of(
                remainingByTier.getOrDefault(tier.id(), COUNTER_UNAVAILABLE),
                tier.totalCapacity(),
                properties.getLimitedThresholdPercent());
    }
}
