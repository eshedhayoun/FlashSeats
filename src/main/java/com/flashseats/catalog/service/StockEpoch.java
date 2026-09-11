package com.flashseats.catalog.service;

import com.flashseats.catalog.repository.EventRepository;
import com.flashseats.catalog.repository.StockCounterRepository;
import java.time.Clock;
import java.util.HashSet;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Refuses to sell from counters a Redis restart may have rolled back.
 *
 * <p><strong>The failure this exists for.</strong> AOF is {@code appendfsync everysec}, so a restart
 * — or a Sentinel failover to a replica that was a moment behind — replays to roughly a second ago.
 * The decrements in that second are gone; the {@code ticket_holds} rows that paid for them are not.
 * The counters come back <em>high</em>, and every seat in that gap sells twice.
 *
 * <p>It is the only inventory failure no ordering of operations can prevent, because the loss
 * happens inside Redis. Everything else in this design is arranged to fail toward invisible seats,
 * which a rebuild recovers. This one cannot be arranged away, only noticed.
 *
 * <p><strong>Every vouching fact lives in Redis, and the verdict is recomputed from scratch each
 * tick.</strong> That is what makes it work on more than one replica. An earlier cut kept a single
 * "something is wrong" flag in memory and stamped a shared key when it fired — so whichever replica
 * noticed first consumed the signal, and its neighbours went on selling from the same rolled-back
 * counters. Here each event carries its own vouched {@code run_id}, so every replica reaches the
 * same verdict independently, and a rebuild on one replica releases the event on all of them.
 */
@Slf4j
@Component
public class StockEpoch {

    private final StockCounterRepository stock;
    private final EventRepository events;
    private final Clock clock;

    /**
     * Events whose counters were vouched for by a different Redis instance than the one running.
     *
     * <p>Replaced wholesale by {@link #check()} rather than accumulated, so it cannot drift from
     * what Redis says and cannot grow without bound.
     */
    private volatile Set<Long> distrusted = Set.of();

    public StockEpoch(StockCounterRepository stock, EventRepository events, Clock clock) {
        this.stock = stock;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Whether this event's counters may still be sold from.
     *
     * <p>On the reserve path, so it is one set lookup and no Redis call.
     */
    public boolean isTrusted(long eventId) {
        return !distrusted.contains(eventId);
    }

    /**
     * Records that an event's counters have been derived afresh and are sound.
     *
     * <p>Called by pre-warm, which builds them from capacity before anything is sold, and by the
     * rebuild, which derives them from the ledger. Nothing else may vouch for a counter, because
     * nothing else knows the number is right.
     */
    public void vouchFor(long eventId) {
        String runId = stock.serverRunId();
        if (runId == null) {
            return;
        }
        stock.vouch(eventId, runId);
        if (distrusted.contains(eventId)) {
            // Take effect now rather than at the next tick; the operator is waiting on it.
            Set<Long> remaining = new HashSet<>(distrusted);
            remaining.remove(eventId);
            distrusted = Set.copyOf(remaining);
            log.warn("Event {} counters vouched for again; selling resumes", eventId);
        }
    }

    @Scheduled(
            fixedDelayString = "${flashseats.catalog.epoch-check-interval-ms}",
            initialDelayString = "${flashseats.catalog.epoch-check-interval-ms}")
    public void check() {
        String current = stock.serverRunId();
        if (current == null) {
            // Redis is unreachable. That is its own failure and the reserve path already fails
            // closed on it; there is nothing to conclude about the counters from here.
            return;
        }

        Set<Long> doubtful = new HashSet<>();
        // MANAGED, not open. A paused event's counters can have been rolled back by the same
        // restart, and skipping it would raise the flag only once an operator resumed the sale —
        // which is to say, once it had already started selling from them.
        for (long eventId : events.findManagedEventIds(clock.instant())) {
            String vouched = stock.vouchedRunId(eventId);
            if (vouched == null) {
                // Nobody ever vouched for this event — a sale seeded outside pre-warm, or the first
                // run after this guard existed. Adopt it rather than halt a sale that is healthy as
                // far as anyone knows.
                stock.vouch(eventId, current);
            } else if (!vouched.equals(current)) {
                doubtful.add(eventId);
            }
        }

        Set<Long> newlyDoubtful = new HashSet<>(doubtful);
        newlyDoubtful.removeAll(distrusted);
        if (!newlyDoubtful.isEmpty()) {
            log.error(
                    "Redis is not the instance that vouched for event(s) {}. Their counters may have"
                            + " lost a second of decrements and can now read HIGH, which oversells."
                            + " Holds are refused for them until each is rebuilt (ADR-004)",
                    newlyDoubtful);
        }
        distrusted = Set.copyOf(doubtful);
    }
}
