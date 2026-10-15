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
 * Refuses to sell from counters a Redis restart may have rolled back (ADR-046). With
 * {@code appendfsync everysec}, a restart or a failover replays to about a second ago: those
 * decrements are gone while the holds that paid for them are not, so counters come back
 * <strong>high</strong>. That is the one inventory failure no ordering prevents, only detects.
 *
 * <p>Each event carries the {@code run_id} that vouched for it, and the verdict is recomputed from
 * Redis every tick, never consumed. Every replica reaches it independently, and a rebuild on one
 * releases the event on all.
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
