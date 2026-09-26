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
 * The operator's address list, held in memory and re-read on a timer. It is consulted on
 * <strong>every</strong> API request, so it never touches the database on that path (ADR-055). Rules:
 *
 * <ol>
 *   <li>The whole table is one snapshot with a TTL, and the TTL is the cross-replica invalidation
 *       (ADR-051).
 *   <li>The load happens outside every monitor: one {@link AtomicReference} is swapped after the read
 *       (invariant 11).
 *   <li>One reload at a time, one per window even when it fails (ADR-056).
 *   <li>A failed reload keeps the previous snapshot.
 * </ol>
 *
 * <p>Rule expiry is evaluated against the clock on read, never baked into the snapshot.
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
     * The snapshot, reloaded at most once per TTL and once at a time. <strong>Single flight</strong>:
     * losers serve the stale snapshot rather than all querying. <strong>Backoff on failure</strong>: a
     * failed reload is stamped like a success, so an unreachable database is retried once per TTL, not
     * per request (ADR-056). An {@link java.util.concurrent.atomic.AtomicBoolean}, never a lock
     * (invariant 11).
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
