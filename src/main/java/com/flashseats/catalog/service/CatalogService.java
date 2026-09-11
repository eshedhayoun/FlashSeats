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
import com.flashseats.catalog.facade.TierSummary;
import com.flashseats.catalog.model.Event;
import com.flashseats.catalog.model.EventStatus;
import com.flashseats.catalog.model.TicketTier;
import com.flashseats.catalog.repository.EventRepository;
import com.flashseats.catalog.repository.StockCounterRepository;
import com.flashseats.catalog.repository.TicketTierRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
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
    private final TicketTierRepository tiers;
    private final StockCounterRepository stock;
    private final StockEpoch epoch;
    private final CatalogProperties properties;
    private final Clock clock;

    public CatalogService(
            EventRepository events,
            TicketTierRepository tiers,
            StockCounterRepository stock,
            StockEpoch epoch,
            CatalogProperties properties,
            Clock clock) {
        this.events = events;
        this.tiers = tiers;
        this.stock = stock;
        this.epoch = epoch;
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

    /**
     * The landing page.
     *
     * <p>Deliberately not wrapped in one transaction: the counters live in Redis now, and holding a
     * pooled database connection open across that round trip — on the single hottest endpoint in the
     * system — would spend the connection pool on a read that needs no consistency between the
     * event's metadata and its inventory.
     */
    public EventDetailResponse getEventDetail(long eventId) {
        Event event = requireEvent(eventId);
        Instant now = clock.instant();
        List<TicketTier> eventTiers = tiers.findByEventIdOrderByPriceCentsDesc(eventId);

        // One round trip for every counter, not one per tier: every visitor loads this before the
        // sale and reloads it while they wait.
        Map<Long, Integer> remainingByTier =
                stock.readAll(eventId, eventTiers.stream().map(TicketTier::getId).toList());

        List<TierResponse> tierResponses =
                eventTiers.stream().map(tier -> toTierResponse(tier, remainingByTier)).toList();

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
     * @return how many tier counters this call created. A repeat pre-warm returns 0 and changes
     *     nothing.
     */
    public int prewarm(long eventId) {
        Event event = requireEvent(eventId);
        if (SaleWindows.statusOf(event, clock.instant()) != EventWindowStatus.UPCOMING) {
            throw new PrewarmWindowClosedException(eventId);
        }

        int seeded = 0;
        for (TicketTier tier : tiers.findByEventIdOrderByPriceCentsDesc(eventId)) {
            if (stock.seedIfAbsent(eventId, tier.getId(), tier.getTotalCapacity())) {
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

    /** Tier id to the capacity it was created with — what a rebuild counts down from. */
    @Transactional(readOnly = true)
    public Map<Long, Integer> getTierCapacities(long eventId) {
        Map<Long, Integer> capacities = new HashMap<>();
        for (TicketTier tier : tiers.findByEventIdOrderByPriceCentsDesc(eventId)) {
            capacities.put(tier.getId(), tier.getTotalCapacity());
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
        return tiers.findByEventIdOrderByPriceCentsDesc(eventId).stream()
                .map(TicketTier::getId)
                .toList();
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
