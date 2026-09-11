package com.flashseats.catalog.repository;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * The live stock counter: {@code catalog:stock:{eventId}:{tierId}}.
 *
 * <p>Owned entirely by {@code catalog}. No other module reads or writes this prefix — {@code hold}
 * moves stock by calling {@link com.flashseats.catalog.facade.CatalogFacade}, so the scripts below
 * are the only code anywhere that touches the key.
 *
 * <p>Redis is the live counter; {@code tier_inventory} is the ledger's last-known-good copy,
 * refreshed by pre-warm and rebuild. If every key here vanished, the rebuild would reconstruct all of
 * them from {@code ticket_tiers}, {@code order_items} and {@code ticket_holds} — which is why losing
 * one is a fault to repair and never a reason to guess.
 */
@Repository
public class StockCounterRepository {

    /** {@link #reserve} took the seats. */
    public static final long RESERVED = 1;

    /** {@link #reserve} found a counter, and it was too low. Genuinely sold out. */
    public static final long INSUFFICIENT = -1;

    /** There is no counter. A fault to alarm on and rebuild, never "sold out" (ADR-004). */
    public static final long NO_COUNTER = -2;

    private final StringRedisTemplate redis;
    private final RedisScript<Long> reserveScript;
    private final RedisScript<Long> restoreScript;

    public StockCounterRepository(
            StringRedisTemplate redis,
            RedisScript<Long> stockReserveScript,
            RedisScript<Long> stockRestoreScript) {
        this.redis = redis;
        this.reserveScript = stockReserveScript;
        this.restoreScript = stockRestoreScript;
    }

    private static final String KEY_PREFIX = "catalog:stock:";

    public static String key(long eventId, long tierId) {
        return KEY_PREFIX + eventId + ":" + tierId;
    }

    /** @return {@link #RESERVED}, {@link #INSUFFICIENT} or {@link #NO_COUNTER} */
    public long reserve(long eventId, long tierId, int quantity) {
        return run(reserveScript, eventId, tierId, quantity);
    }

    /** @return the counter's new value, or {@link #NO_COUNTER} if there was nothing to restore */
    public long restore(long eventId, long tierId, int quantity) {
        return run(restoreScript, eventId, tierId, quantity);
    }

    /**
     * One round trip for a whole event.
     *
     * @return tier id to remaining seats. <strong>A tier with no counter is absent, never zero</strong>
     *     — the caller needs to tell "nothing left" from "nothing known" (ADR-035, ADR-040).
     */
    public Map<Long, Integer> readAll(long eventId, List<Long> tierIds) {
        Map<Long, Integer> remaining = new HashMap<>();
        if (tierIds.isEmpty()) {
            return remaining;
        }
        List<String> keys = tierIds.stream().map(tierId -> key(eventId, tierId)).toList();
        List<String> values = redis.opsForValue().multiGet(keys);
        if (values == null) {
            return remaining;
        }
        for (int i = 0; i < tierIds.size(); i++) {
            String value = values.get(i);
            if (value != null) {
                remaining.put(tierIds.get(i), Integer.parseInt(value));
            }
        }
        return remaining;
    }

    /**
     * Seeds a counter only if it does not already exist.
     *
     * <p>{@code SET NX} is what makes pre-warm repeatable. It is not what makes pre-warm safe — the
     * {@code UPCOMING} window check does that, because overwriting a live counter with
     * {@code total_capacity} would resurrect every ticket already sold (ADR-004).
     *
     * @return true if this call created the counter
     */
    public boolean seedIfAbsent(long eventId, long tierId, int capacity) {
        return Boolean.TRUE.equals(
                redis.opsForValue().setIfAbsent(key(eventId, tierId), String.valueOf(capacity)));
    }

    /**
     * Overwrites a counter outright.
     *
     * <p>The only legal caller is the rebuild, which derives the value from the ledger under a lock.
     * Everything else moves stock through {@link #reserve} and {@link #restore}.
     */
    public void overwrite(long eventId, long tierId, int remaining) {
        redis.opsForValue().set(key(eventId, tierId), String.valueOf(remaining));
    }

    // ------------------------------------------------------------------- epoch

    /**
     * The running Redis server's {@code run_id}, which changes on every restart and on failover.
     *
     * <p>The only way to notice that counters may have silently lost writes. AOF is
     * {@code appendfsync everysec}, so a restart replays to about a second ago and the decrements in
     * that second are gone — while the {@code ticket_holds} rows they paid for are still there. The
     * counters come back <em>high</em>, which is the one direction that oversells.
     *
     * @return null if the server could not be asked
     */
    public String serverRunId() {
        return redis.execute((RedisCallback<String>) connection -> {
            Properties info = connection.serverCommands().info("server");
            return info == null ? null : info.getProperty("run_id");
        });
    }

    /**
     * Every event that currently has at least one live counter.
     *
     * <p>Exactly the set a Redis restart puts in doubt: a counter that exists may have rolled back,
     * while an event with none has nothing to roll back to. Scanned rather than derived from SQL so
     * the answer describes what Redis actually holds, which is the thing in question.
     */
    public Set<Long> eventIdsWithCounters() {
        Set<Long> eventIds = new HashSet<>();
        try (Cursor<String> keys =
                redis.scan(ScanOptions.scanOptions().match(KEY_PREFIX + "*").count(256).build())) {
            while (keys.hasNext()) {
                // catalog:stock:{eventId}:{tierId} — anything else under the prefix is not a counter.
                String[] parts = keys.next().split(":");
                if (parts.length == 4) {
                    eventIds.add(Long.parseLong(parts[2]));
                }
            }
        }
        return eventIds;
    }

    /** The {@code run_id} that was running when the counters were last known to be sound. */
    public String readTrustedRunId() {
        return redis.opsForValue().get(RUN_ID_KEY);
    }

    /** Records that the counters are sound as of this server incarnation. */
    public void trustRunId(String runId) {
        redis.opsForValue().set(RUN_ID_KEY, runId);
    }

    private static final String RUN_ID_KEY = "catalog:stock:runid";

    private long run(RedisScript<Long> script, long eventId, long tierId, int quantity) {
        Long result = redis.execute(
                script, List.of(key(eventId, tierId)), String.valueOf(quantity));
        // Our scripts always return a number; a null here means the call never reached Redis.
        return result == null ? NO_COUNTER : result;
    }
}
