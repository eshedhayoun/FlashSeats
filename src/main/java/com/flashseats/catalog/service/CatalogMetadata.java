package com.flashseats.catalog.service;

import com.flashseats.catalog.config.CatalogProperties;
import com.flashseats.catalog.exception.CatalogErrors;
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
 * Event and tier metadata read from memory instead of a pooled connection (ADR-051). These rows
 * barely change, yet every window check and tier lookup was its own transaction, and at 3–10
 * concurrent sales that traffic exhausted the pool first (ADR-049). Four rules:
 *
 * <ul>
 *   <li><strong>Every entry expires</strong>: the TTL is the cross-replica invalidation, or a paused
 *       sale answers {@code OPEN} on the other replicas forever.
 *   <li><strong>Loads happen outside the map</strong>, never in {@code computeIfAbsent}: blocking
 *       JDBC inside its per-bin lock pins carrier threads (ADR-022).
 *   <li><strong>A miss is never cached</strong>: seed SQL inserts rows behind the app's back.
 *   <li><strong>Recovery paths do not read this</strong>: {@link #tiersUncached} serves pre-warm and
 *       rebuild, where a stale tier list would leave a tier with no counter (ADR-004, ADR-046).
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
     * @throws com.flashseats.shared.error.FlashSeatsException {@code EVENT_NOT_FOUND} — see
     *     {@link CatalogErrors} — if there is no such row. Deliberately not remembered — see the
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
     * Every {@code PUBLISHED} or {@code PAUSED} event, ordered by sale start. Cached for
     * <strong>availability, not cost</strong>: it lets {@code PromotionWorker.tick()} run without a
     * pooled connection, because the component that protects the pool must not starve on it (ADR-051).
     * It is not clock-dependent, so caching it is safe.
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
                events.findById(eventId).orElseThrow(() -> CatalogErrors.eventNotFound(eventId)));
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
