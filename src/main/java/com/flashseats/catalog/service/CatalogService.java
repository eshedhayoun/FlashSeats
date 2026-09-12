package com.flashseats.catalog.service;

import com.flashseats.catalog.config.CatalogProperties;
import com.flashseats.catalog.dto.EventDetailResponse;
import com.flashseats.catalog.dto.EventListItemResponse;
import com.flashseats.catalog.dto.TierResponse;
import com.flashseats.catalog.exception.EventNotFoundException;
import com.flashseats.catalog.exception.EventNotPausableException;
import com.flashseats.catalog.exception.PrewarmWindowClosedException;
import com.flashseats.catalog.exception.TierNotFoundException;
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

/** Event metadata, sale windows, and every movement of the inventory counter. */
@Slf4j
@Service
public class CatalogService {

    /**
     * Stands in for a count that cannot be read — a fault, never "sold out".
     * Mirrored on {@link com.flashseats.catalog.facade.CatalogFacade#COUNTER_UNAVAILABLE}, which is
     * the value other modules see.
     */
    public static final int COUNTER_UNAVAILABLE = -1;

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

    @Transactional(readOnly = true)
    public List<EventListItemResponse> listEvents() {
        Instant now = clock.instant();
        return events.findByStatusOrderBySaleStartTimeAsc(EventStatus.PUBLISHED).stream()
                .map(EventRow::of)
                .map(event -> new EventListItemResponse(
                        event.id(),
                        event.title(),
                        event.venueName(),
                        event.eventStartTime(),
                        event.saleStartTime(),
                        SaleWindows.statusOf(event, now)))
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
                SaleWindows.statusOf(event, now),
                now,
                tierResponses);
    }

    // ----------------------------------------------------------- facade reads

    public TierSummary getTierSummary(long eventId, long tierId) {
        EventRow event = metadata.event(eventId);
        TierRow tier = metadata.tiers(eventId).stream()
                .filter(candidate -> candidate.id() == tierId)
                .findFirst()
                .orElseThrow(() -> new TierNotFoundException(eventId, tierId));

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
                SaleWindows.statusOf(event, clock.instant()));
    }

    public EventSummary getEventSummary(long eventId) {
        EventRow event = metadata.event(eventId);
        return new EventSummary(
                event.id(),
                event.title(),
                event.venueName(),
                event.eventStartTime(),
                event.saleStartTime(),
                event.saleEndTime(),
                SaleWindows.statusOf(event, clock.instant()));
    }

    public EventWindowStatus getWindowStatus(long eventId) {
        return SaleWindows.statusOf(metadata.event(eventId), clock.instant());
    }

    @Transactional(readOnly = true)
    public List<Long> findOpenEventIds() {
        return events.findOpenEventIds(clock.instant());
    }

    /** Open <em>or paused</em> — what an operator is still answerable for. See the repository. */
    @Transactional(readOnly = true)
    public List<Long> findManagedEventIds() {
        return events.findManagedEventIds(clock.instant());
    }

    /**
     * Halts a live sale, or resumes it.
     *
     * <p>Nothing is torn down and nothing is lost. The waiting room's ZSET is untouched, every
     * position survives, live passes and admissions run out their own clocks, and stock stays exactly
     * where it is — so resuming returns every buyer to precisely where they were. That is the same
     * reasoning as ADR-035's refusal to delete a waiting room on a sold-out reading: the state an
     * operator can destroy in a moment takes a sale to rebuild.
     *
     * <p>Idempotent, so a second click is not an error.
     *
     * <p><strong>The eviction is published, not performed.</strong> It runs {@code AFTER_COMMIT}
     * (see {@link CatalogMetadata}), because evicting inline leaves a window in which a concurrent
     * reader re-caches the row this transaction is about to change — and the entry it writes would
     * be fresh, so an operator's pause could fail on the replica that served it. Published
     * unconditionally, including on the no-op branch, because a cache that is already correct loses
     * nothing by being told twice.
     *
     * @throws EventNotPausableException if the event is {@code DRAFT} or {@code CANCELLED}, where
     *     "paused" would mean nothing and un-pausing would publish something nobody published
     */
    @Transactional
    public EventStatus setPaused(long eventId, boolean paused) {
        Event event = requireEvent(eventId);
        EventStatus current = event.getStatus();

        if (current != EventStatus.PUBLISHED && current != EventStatus.PAUSED) {
            throw new EventNotPausableException(eventId, current);
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
     * Total remaining across an event's tiers — the promoter's admission bound.
     *
     * <p><strong>"No counter" is never "zero"</strong> (ADR-035). Summing the counters that do exist
     * would make an un-warmed event report {@code 0} and be indistinguishable from a sold-out one,
     * and the caller would tell an entire waiting room the sale had ended because a key was missing —
     * ADR-004's failure, one module over. If any tier is missing its counter the whole answer is a
     * fault.
     *
     * <p>The tier list comes from {@code ticket_tiers}, not from the counters. That is the whole
     * guard: asking the counters which tiers exist would make a wholly evicted event look like an
     * event with no tiers, whose remaining stock sums to zero.
     *
     * @return remaining seats across the event, or {@link #COUNTER_UNAVAILABLE} if any tier has no
     *     counter
     */
    public int getRemainingForEvent(long eventId) {
        List<Long> tierIds = tierIds(eventId);
        Map<Long, Integer> counters = stock.readAll(eventId, tierIds);

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
     * Atomically takes {@code quantity} seats from a tier.
     *
     * <p>One Lua script, so the read, the comparison and the decrement cannot interleave. The
     * three-way answer is the point: whether the counter was <em>readable</em> is decided in the same
     * atomic step as whether it was <em>sufficient</em>. Recovering that distinction afterwards, by
     * re-reading the counter, raced — a concurrent restore between the two calls turned a fault into
     * an ordinary "sold out".
     *
     * <p><strong>Not transactional, and it must not be called from inside a transaction.</strong>
     * Redis cannot roll back, so a decrement inside a SQL transaction that later fails would leak
     * the seats for good (ADR-023). The caller writes its hold row next and calls {@link #restore}
     * if it cannot.
     */
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
     * Returns {@code quantity} seats to a tier.
     *
     * <p>Callers must have won the settle-once claim on the hold first, and that claim must already
     * have committed — the claim, not this call, is what guarantees a hold's seats come back exactly
     * once (ADR-019), and incrementing before the commit would return seats that a rollback then
     * puts back on hold.
     *
     * <p>A missing counter is left missing. Creating one here would rebuild inventory out of
     * whichever hold happened to expire next, which is ADR-004's prohibition; the seats are reported
     * by {@code flashseats.stock.drift} and returned by a rebuild.
     */
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
     * Seeds inventory from {@code total_capacity}, and only while the sale is {@code UPCOMING}.
     *
     * <p>The window check is the whole point. Seeding an open sale from capacity would silently
     * resurrect every ticket already sold — the highest-severity defect the design review found
     * (ADR-004). Recovery during a live sale is a rebuild from the ledger, never a reseed.
     *
     * <p><strong>The tier list is read uncached.</strong> Events and tiers are inserted straight
     * into PostgreSQL by the seed scripts, so a cached list could be a subset — and a tier left
     * without a counter answers {@code 503} for the rest of the sale.
     *
     * @return how many tier counters this call created. A repeat pre-warm returns 0 and changes
     *     nothing.
     */
    public int prewarm(long eventId) {
        EventRow event = EventRow.of(requireEvent(eventId));
        if (SaleWindows.statusOf(event, clock.instant()) != EventWindowStatus.UPCOMING) {
            throw new PrewarmWindowClosedException(eventId);
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
        return events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
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
        return AvailabilityBuckets.of(
                remainingByTier.getOrDefault(tier.id(), COUNTER_UNAVAILABLE),
                tier.totalCapacity(),
                properties.getLimitedThresholdPercent());
    }
}
