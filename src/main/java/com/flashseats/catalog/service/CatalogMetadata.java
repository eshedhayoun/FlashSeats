package com.flashseats.catalog.service;

import com.flashseats.catalog.config.CatalogProperties;
import com.flashseats.catalog.exception.EventNotFoundException;
import com.flashseats.catalog.model.EventStatus;
import com.flashseats.catalog.repository.EventRepository;
import com.flashseats.catalog.repository.TicketTierRepository;
import com.flashseats.shared.cache.DerivedStateCache;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Event and tier metadata, read from memory instead of from a pooled connection (ADR-051).
 *
 * <p><strong>Why this exists.</strong> {@code events} changes only when an operator pauses or
 * resumes a sale and {@code ticket_tiers} never changes at all, yet every window check, event
 * summary, tier summary and tier-id lookup was its own PostgreSQL transaction — on the landing page,
 * the queue-status poll and the rehydration endpoint. At {@code E = 3..10} concurrent sales that
 * traffic, not admission, was the first thing to exhaust the connection pool (ADR-049).
 *
 * <p><strong>Four rules hold this together, and each one is a defect that was written first.</strong>
 *
 * <ul>
 *   <li><strong>Every entry expires.</strong> The TTL <em>is</em> the cross-replica invalidation:
 *       eviction reaches only the replica that served the operator's call, so without a TTL a paused
 *       sale keeps answering {@code OPEN} on the other two for the life of the process — and
 *       {@code getWindowStatus} and {@code getTierSummary} gate queue join, holds and checkout, so
 *       pause would stop nothing. It also bounds rows created out of band by the seed SQL.
 *   <li><strong>Loads happen outside the map.</strong> Never {@code computeIfAbsent}: that runs the
 *       loader inside {@code ConcurrentHashMap}'s per-bin monitor, and blocking JDBC inside
 *       {@code synchronized} pins carrier threads on JDK 21 — the reason Redisson was removed
 *       (ADR-022). A cold key at sale open would stall every carrier at once. Two threads racing the
 *       same miss both query and both write the same answer, which costs one extra query and no
 *       correctness.
 *   <li><strong>A miss is never cached.</strong> Events and tiers are inserted straight into
 *       PostgreSQL by {@code docker/seed/*.sql}, so a remembered "no such event" would outlive the
 *       insert that created it.
 *   <li><strong>Recovery paths do not read this.</strong> {@link #tiersUncached} is what
 *       {@code prewarm} and a rebuild use. A stale tier list would make pre-warm seed a subset —
 *       leaving a tier with no counter, which answers {@code 503} for the rest of the sale (ADR-004)
 *       — or make a rebuild write counters derived from the wrong set of tiers (ADR-046).
 * </ul>
 */
@Slf4j
@Component
public class CatalogMetadata implements DerivedStateCache {

    private final EventRepository events;
    private final TicketTierRepository tiers;
    private final CatalogProperties properties;
    private final Clock clock;

    /** The single key the event-list cache lives under; it is not per event. */
    private static final long ALL = -1L;

    private final Map<Long, Entry<EventRow>> eventCache = new ConcurrentHashMap<>();
    private final Map<Long, Entry<List<TierRow>>> tierCache = new ConcurrentHashMap<>();
    private final Map<Long, Entry<List<EventRow>>> listCache = new ConcurrentHashMap<>();

    private final Counter hits;
    private final Counter misses;

    public CatalogMetadata(
            EventRepository events,
            TicketTierRepository tiers,
            CatalogProperties properties,
            Clock clock,
            MeterRegistry meters) {
        this.events = events;
        this.tiers = tiers;
        this.properties = properties;
        this.clock = clock;
        this.hits = Counter.builder("flashseats.catalog.metadata.cache")
                .description("Metadata reads served without a database connection")
                .tag("result", "hit")
                .register(meters);
        this.misses = Counter.builder("flashseats.catalog.metadata.cache")
                .description("Metadata reads that cost a database connection")
                .tag("result", "miss")
                .register(meters);
    }

    /**
     * One event's metadata.
     *
     * @throws EventNotFoundException if there is no such row. Deliberately not remembered — see the
     *     class note on misses.
     */
    public EventRow event(long eventId) {
        return read(eventCache, eventId, properties.getMetadataEventTtlMs(), () -> loadEvent(eventId));
    }

    /** An event's tiers, most expensive first — the order the landing page renders. */
    public List<TierRow> tiers(long eventId) {
        return read(tierCache, eventId, properties.getMetadataTierTtlMs(), () -> loadTiers(eventId));
    }

    /**
     * Every {@code PUBLISHED} or {@code PAUSED} event, ordered by sale start.
     *
     * <p><strong>This one is cached for availability, not for cost.</strong> The three list reads
     * derived from it — open ids, managed ids, the public listing — are a handful of queries a second
     * cluster-wide, so caching them saves nothing worth mentioning. What it buys is that
     * {@code PromotionWorker.tick()} needs <em>no pooled connection</em>.
     *
     * <p>That matters because the first version left it uncached on exactly the cost argument, and the
     * Pass 8 drill showed the argument was the wrong one. Under pool pressure the tick waited on the
     * same queue as the buyers it existed to admit — one logged wait was <strong>16 seconds inside a
     * one-second tick</strong> — so nobody was promoted, the waiting room did not drain, and the buyers kept
     * polling. The component that protects the pool must not be able to starve on it.
     *
     * <p>Safe to cache because, unlike the reads derived from it, it is not parameterised by the
     * clock: the window comparison happens in memory against this snapshot.
     */
    public List<EventRow> selectableEvents() {
        return read(listCache, ALL, properties.getMetadataEventTtlMs(), this::loadSelectable);
    }

    /**
     * The tiers as PostgreSQL has them right now, bypassing the cache and dropping what it held.
     *
     * <p>For pre-warm and for a rebuild only. Both write inventory counters derived from this list,
     * and a list that is merely probably right produces a counter that is definitely wrong.
     */
    public List<TierRow> tiersUncached(long eventId) {
        List<TierRow> fresh = loadTiers(eventId);
        if (properties.isMetadataCacheEnabled()) {
            tierCache.put(eventId, entry(fresh, properties.getMetadataTierTtlMs()));
        }
        return fresh;
    }

    /**
     * Drops one event's snapshots, and the list they appear in.
     *
     * <p>Unconditional, so a no-op pause is still safe. The list goes too because pausing is exactly
     * what moves an event out of {@code findOpenEventIds}.
     */
    public void invalidate(long eventId) {
        eventCache.remove(eventId);
        tierCache.remove(eventId);
        listCache.clear();
    }

    /**
     * Drops everything.
     *
     * <p>Used by the test fixture, which truncates {@code events} and {@code ticket_tiers} with
     * {@code RESTART IDENTITY} between methods: ids come back as 1 and a surviving entry would
     * describe the previous test's sale. Clearing here is the same seam as truncating the tables,
     * and it is what lets the suite run with the cache <em>on</em> rather than proving nothing by
     * disabling it.
     */
    @Override
    public void invalidateAll() {
        eventCache.clear();
        tierCache.clear();
        listCache.clear();
    }

    /**
     * Evicts once the change is actually committed.
     *
     * <p>{@code fallbackExecution} so that a caller who somehow changes metadata outside a
     * transaction still evicts; a stale entry is the one failure this class cannot report.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    void onEventMetadataChanged(EventMetadataChanged changed) {
        invalidate(changed.eventId());
        log.debug("Dropped cached metadata for event {}", changed.eventId());
    }

    // ----------------------------------------------------------------- internals

    private <T> T read(Map<Long, Entry<T>> cache, long eventId, long ttlMs, Supplier<T> loader) {
        if (!properties.isMetadataCacheEnabled()) {
            return loader.get();
        }

        long now = clock.millis();
        Entry<T> cached = cache.get(eventId);
        if (cached != null && cached.expiresAtMillis() > now) {
            hits.increment();
            return cached.value();
        }

        misses.increment();
        // Outside the map, and therefore outside any monitor: see the class note on pinning.
        T loaded = loader.get();
        if (cache.size() < properties.getMetadataCacheMaxEvents() || cache.containsKey(eventId)) {
            cache.put(eventId, entry(loaded, ttlMs));
        } else {
            // The bound is a leak guard, not an eviction policy. Dropping a live entry to make room
            // for this one would be worse than serving this one uncached.
            purgeExpired(cache, now);
        }
        return loaded;
    }

    private <T> void purgeExpired(Map<Long, Entry<T>> cache, long now) {
        cache.values().removeIf(entry -> entry.expiresAtMillis() <= now);
    }

    private <T> Entry<T> entry(T value, long ttlMs) {
        return new Entry<>(value, clock.millis() + ttlMs);
    }

    private EventRow loadEvent(long eventId) {
        return EventRow.of(
                events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId)));
    }

    private List<EventRow> loadSelectable() {
        return events.findByStatusInOrderBySaleStartTimeAsc(
                        List.of(EventStatus.PUBLISHED, EventStatus.PAUSED))
                .stream()
                .map(EventRow::of)
                .toList();
    }

    private List<TierRow> loadTiers(long eventId) {
        return tiers.findByEventIdOrderByPriceCentsDesc(eventId).stream()
                .map(TierRow::of)
                .toList();
    }

    private record Entry<T>(T value, long expiresAtMillis) {}
}
