package com.flashseats.catalog.repository;

import com.flashseats.catalog.facade.ReserveResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import org.springframework.data.redis.core.RedisCallback;
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
 * <p><strong>There is no copy of this number in PostgreSQL.</strong> The database holds the ledger
 * it can be derived from — {@code ticket_tiers} minus what {@code order_items} sold and
 * {@code ticket_holds} is holding — and that derivation is the rebuild. Losing a key is therefore a
 * fault to repair from the ledger, and never a reason to guess.
 */
@Repository
public class StockCounterRepository {

    /**
     * What the scripts answer with.
     *
     * <p>Private, and deliberately so. These numbers are the Lua protocol and nothing above this
     * class should speak it — {@code -1} here would mean "sold out" while {@code -1} one layer up
     * means "no counter at all", which is the exact pair of meanings this design spends its effort
     * keeping apart.
     */
    private static final long RESERVED = 1;

    private static final long NO_COUNTER = -2;

    private static final String COUNTER_PREFIX = "catalog:stock:";
    private static final String VOUCH_PREFIX = "catalog:vouch:";

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

    public static String key(long eventId, long tierId) {
        return COUNTER_PREFIX + eventId + ":" + tierId;
    }

    /** Takes seats atomically, telling a sold-out tier from an unreadable one. */
    public ReserveResult reserve(long eventId, long tierId, int quantity) {
        long answer = run(reserveScript, eventId, tierId, quantity);
        if (answer == RESERVED) {
            return ReserveResult.RESERVED;
        }
        return answer == NO_COUNTER ? ReserveResult.COUNTER_MISSING : ReserveResult.INSUFFICIENT;
    }

    /**
     * Gives seats back.
     *
     * @return false if there was no counter to give them back to. Those seats are invisible until a
     *     rebuild runs, which is the safe direction — creating a counter here would conjure
     *     inventory out of one expiring hold (ADR-004).
     */
    public boolean restore(long eventId, long tierId, int quantity) {
        return run(restoreScript, eventId, tierId, quantity) != NO_COUNTER;
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
     * Which Redis instance was running when this event's counters were last derived from scratch.
     *
     * <p>Per event, and in Redis rather than in a replica's memory, because both halves matter: per
     * event so that rebuilding one sale does not vouch for its neighbours, and in Redis so that
     * every replica reaches the same verdict and a rebuild performed on one releases the event on
     * all of them.
     *
     * @return null if nothing has ever vouched for this event
     */
    public String vouchedRunId(long eventId) {
        return redis.opsForValue().get(VOUCH_PREFIX + eventId);
    }

    /** Records that this event's counters are sound as of the given server incarnation. */
    public void vouch(long eventId, String runId) {
        redis.opsForValue().set(VOUCH_PREFIX + eventId, runId);
    }

    private long run(RedisScript<Long> script, long eventId, long tierId, int quantity) {
        Long result = redis.execute(
                script, List.of(key(eventId, tierId)), String.valueOf(quantity));
        // Our scripts always return a number; a null here means the call never reached Redis.
        return result == null ? NO_COUNTER : result;
    }
}
