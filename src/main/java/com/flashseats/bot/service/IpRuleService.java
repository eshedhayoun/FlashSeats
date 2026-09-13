package com.flashseats.bot.service;

import com.flashseats.bot.config.BotProperties;
import com.flashseats.bot.model.IpRule;
import com.flashseats.bot.model.IpRuleAction;
import com.flashseats.bot.repository.IpRuleRepository;
import com.flashseats.shared.cache.DerivedStateCache;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The operator's address list, held in memory and re-read on a timer.
 *
 * <p><strong>This is consulted on every single API request, so it may never touch the database on
 * that path.</strong> A per-request query would put the rate limiter — whose entire job is to keep
 * load off the system — inside the connection pool it is protecting, queued behind the buyers it
 * exists to shield. That is ADR-051's trap ("a job that protects a resource by reading that
 * resource") with the pool as the resource and the filter as the job.
 *
 * <p>Four rules, each of which is a defect somewhere in this repository's history:
 *
 * <ol>
 *   <li><strong>The whole table is one snapshot, with a TTL, and the TTL <em>is</em> the
 *       cross-replica invalidation.</strong> An operator's call evicts on the replica that served
 *       it; the others pick the change up when their snapshot expires. Without an expiry a block
 *       would be permanent on one replica and absent on the other two, for the life of the process
 *       (ADR-051).
 *   <li><strong>The load happens outside every monitor.</strong> There is no map to lock: a single
 *       {@link AtomicReference} is swapped after the read completes. Blocking JDBC inside a
 *       {@code ConcurrentHashMap} bin pins carrier threads on JDK 21 (invariant 11), and this runs
 *       on the hottest path in the system.
 *   <li><strong>One reload at a time, and one per window even when it fails.</strong> Both halves
 *       are about behaviour under trouble rather than under load: without single flight, every
 *       thread arriving at an expiry issues its own query; without stamping the failed attempt, an
 *       unreachable database is retried <em>per request</em>. Either way the component whose job is
 *       shedding load becomes the thing generating it, at exactly the wrong moment.
 *   <li><strong>A failed reload keeps the previous snapshot.</strong> The database being briefly
 *       unreachable must not unblock every address at once — nor block every address at once. The
 *       last known answer is better than either.
 * </ol>
 *
 * <p>Expiry is evaluated against the clock on read, never baked into the snapshot: a rule that
 * expires between two reloads must stop applying the moment it expires, not when the TTL next
 * lapses. Same reasoning as ADR-051's rule about never caching a value derived from the clock.
 */
@Slf4j
@Service
public class IpRuleService implements DerivedStateCache {

    private final IpRuleRepository rules;
    private final BotProperties properties;
    private final Clock clock;

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>(Snapshot.empty());

    /** Single-flight guard. Never a lock: blocking here would pin carrier threads (invariant 11). */
    private final AtomicBoolean reloading = new AtomicBoolean(false);

    public IpRuleService(IpRuleRepository rules, BotProperties properties, Clock clock) {
        this.rules = rules;
        this.properties = properties;
        this.clock = clock;
    }

    /** The standing decision about an address, or {@code null} if there is none. */
    public IpRuleAction actionFor(String ipAddress) {
        if (ipAddress == null) {
            return null;
        }
        Rule rule = current().rules().get(ipAddress);
        if (rule == null) {
            return null;
        }
        // Derived from the live clock, never from the snapshot: a rule that lapses between reloads
        // has to stop applying at its expiry, not at the cache's.
        if (rule.expiresAt() != null && !clock.instant().isBefore(rule.expiresAt())) {
            return null;
        }
        return rule.action();
    }

    @Override
    public void invalidateAll() {
        snapshot.set(Snapshot.empty());
    }

    // ------------------------------------------------------------ operator surface

    @Transactional(readOnly = true)
    public List<IpRule> all() {
        return rules.findAll();
    }

    @Transactional
    public IpRule upsert(String ipAddress, IpRuleAction action, String reason, Instant expiresAt) {
        IpRule rule = rules.findByIpAddress(ipAddress).orElseGet(IpRule::new);
        rule.setIpAddress(ipAddress);
        rule.setAction(action);
        rule.setReason(reason);
        rule.setExpiresAt(expiresAt);
        IpRule saved = rules.save(rule);
        invalidateAll();
        return saved;
    }

    @Transactional
    public void remove(String ipAddress) {
        rules.deleteByIpAddress(ipAddress);
        invalidateAll();
    }

    // ----------------------------------------------------------------- internals

    /**
     * The snapshot, reloaded at most once per TTL and at most once at a time.
     *
     * <p>Two guards, and both are about what this method does when it is <em>not</em> working:
     *
     * <ul>
     *   <li><strong>Single flight.</strong> Without {@code reloading}, every thread arriving between
     *       the expiry and the first successful {@code set} issues its own {@code findAll()} — at a
     *       few thousand requests a second that is a pool spike every TTL, from the component whose
     *       job is keeping load off the pool. The losers serve the stale snapshot, which is exactly
     *       what a TTL means anyway.
     *   <li><strong>Backoff on failure.</strong> The failure path stamps the clock like a success,
     *       so an unreachable database is retried once per TTL rather than once per request. Without
     *       it, a database outage makes this filter open a connection <em>per request</em> — the
     *       load-shedder becoming the load, during the incident it exists to survive.
     * </ul>
     *
     * <p>{@link java.util.concurrent.atomic.AtomicBoolean}, never a lock. This runs on every API
     * request, and blocking here would pin carrier threads on JDK 21 (invariant 11).
     */
    private Snapshot current() {
        long now = clock.millis();
        Snapshot held = snapshot.get();
        if (held.isFreshAt(now, properties.getIpRuleCacheTtlMs())) {
            return held;
        }

        // Single flight. Everyone else serves the stale snapshot, which is what a TTL means anyway.
        if (!reloading.compareAndSet(false, true)) {
            return held;
        }
        try {
            Map<String, Rule> loaded = new HashMap<>();
            for (IpRule rule : rules.findAll()) {
                loaded.put(rule.getIpAddress(), new Rule(rule.getAction(), rule.getExpiresAt()));
            }
            return replaceWith(new Snapshot(Map.copyOf(loaded), now));

        } catch (RuntimeException unreadable) {
            // Keep what we had, and — the part that matters — stamp the attempt anyway, so an
            // unreachable database is asked once per window rather than once per request.
            // Unblocking everyone because the database hiccuped is worse than a stale rule;
            // blocking everyone is worse still; and retrying per request is worse than both.
            log.warn("Could not reload ip_rules; keeping the previous snapshot", unreadable);
            return replaceWith(new Snapshot(held.rules(), now));

        } finally {
            reloading.set(false);
        }
    }

    private Snapshot replaceWith(Snapshot updated) {
        snapshot.set(updated);
        return updated;
    }

    private record Rule(IpRuleAction action, Instant expiresAt) {}

    /**
     * @param attemptedAtMillis when this snapshot was last <strong>attempted</strong>, whether or not
     *     the attempt succeeded. Success and failure stamp it alike; that is the backoff.
     */
    private record Snapshot(Map<String, Rule> rules, long attemptedAtMillis) {

        static Snapshot empty() {
            return new Snapshot(Map.of(), 0L);
        }

        boolean isFreshAt(long now, long ttlMs) {
            return attemptedAtMillis != 0L && attemptedAtMillis + ttlMs > now;
        }
    }
}
