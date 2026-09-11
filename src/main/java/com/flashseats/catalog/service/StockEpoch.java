package com.flashseats.catalog.service;

import com.flashseats.catalog.repository.StockCounterRepository;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Notices that Redis restarted, and refuses to sell from counters that may have lost writes.
 *
 * <p><strong>The failure this exists for.</strong> AOF is {@code appendfsync everysec}, so a restart
 * — or a Sentinel failover to a replica that was a moment behind — replays to roughly a second ago.
 * The decrements in that second are gone; the {@code ticket_holds} rows they paid for are not. The
 * counters come back <em>high</em>, and every seat in that gap is sold twice. It is the one
 * direction this whole design refuses to fail in, and it is the only one that no ordering of
 * operations can prevent, because the loss happens inside Redis.
 *
 * <p>{@code catalog.md} has always said a rebuild is mandatory after any Redis restart. This is what
 * makes that true rather than a note someone has to remember at three in the morning.
 *
 * <p>Trust is restored <strong>per event</strong>, by rebuilding it. An operator can therefore bring
 * one sale back at a time instead of having to repair everything before anything can sell.
 */
@Slf4j
@Component
public class StockEpoch {

    private final StockCounterRepository stock;

    /**
     * Events whose counters existed when a restart was noticed and have not been repaired since.
     *
     * <p>Held as a set of the doubtful rather than a single "something is wrong" flag, so that an
     * event created <em>after</em> the restart is trusted without anyone having to say so, and an
     * event repaired by a rebuild stops being doubtful without vouching for its neighbours.
     */
    private final Set<Long> distrusted = ConcurrentHashMap.newKeySet();

    public StockEpoch(StockCounterRepository stock) {
        this.stock = stock;
    }

    /**
     * Whether this event's counters may still be sold from.
     *
     * <p>On the reserve path, so it is one set lookup and no Redis call.
     */
    public boolean isTrusted(long eventId) {
        return !distrusted.contains(eventId);
    }

    /** Records that an event's counters have been derived afresh and are sound again. */
    public void trust(long eventId) {
        String runId = stock.serverRunId();
        if (runId != null) {
            stock.trustRunId(runId);
        }
        if (distrusted.remove(eventId)) {
            log.warn("Event {} counters trusted again; selling resumes", eventId);
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

        String sound = stock.readTrustedRunId();
        if (sound == null) {
            // Nothing has ever vouched for these counters — a first boot, or the first run after
            // this guard was added. Adopt the current server rather than halting a healthy sale.
            stock.trustRunId(current);
            return;
        }
        if (current.equals(sound)) {
            return;
        }

        Set<Long> doubtful = stock.eventIdsWithCounters();
        distrusted.addAll(doubtful);
        // Stamped immediately so this is reported once per restart, not once per tick.
        stock.trustRunId(current);

        log.error(
                "Redis restarted (run_id {} -> {}). Counters for event(s) {} may have lost up to a"
                        + " second of decrements and can now read HIGH, which oversells. Holds are"
                        + " refused for them until each is rebuilt (ADR-004)",
                sound,
                current,
                doubtful);
    }
}
