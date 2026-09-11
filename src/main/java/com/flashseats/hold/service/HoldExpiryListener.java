package com.flashseats.hold.service;

import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Turns a {@code hold:{token}} key expiring into an immediate reclaim.
 *
 * <p>This is a <strong>latency</strong> component. {@link HoldReconciliationSweeper} already
 * reclaims every expired hold and is untouched by this class existing; what this removes is the up
 * to {@code flashseats.hold.sweeper-interval-ms} that seats spend invisible after a buyer walks
 * away — the difference between a sold-out-looking sale and one that keeps selling.
 *
 * <p>It can never be the guarantee, because keyspace notifications are <strong>at-most-once
 * pub/sub</strong>: a replica that is restarting, or whose connection drops for a moment, loses the
 * event permanently and nothing redelivers it. A design that relied on this would leak inventory
 * every time a pod restarted.
 *
 * <h2>The two traps this class is shaped around</h2>
 *
 * <p><strong>1. Every replica receives every expiry.</strong> That is not a flaw to work around —
 * it is why no coordination is needed. All three call {@link HoldService#reclaimExpired}, all three
 * reach the settle-once claim, and exactly one {@code UPDATE ... WHERE status = 'ACTIVE'} returns
 * rowcount 1. Restoring the seats here instead would restore them three times.
 *
 * <p><strong>2. {@code __keyevent@0__:expired} is one channel for the whole database.</strong> It
 * carries queue passes, admission sessions, payment in-flight guards and rate-limit buckets — during
 * a sale, thousands a second, to every replica. There is no server-side filter for it, so the prefix
 * check below is the only one there is, and it is the first thing this method does for that reason.
 */
@Slf4j
@Component
public class HoldExpiryListener implements MessageListener {

    private final HoldService holds;

    public HoldExpiryListener(HoldService holds) {
        this.holds = holds;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        // The message body IS the key name — that is what `notify-keyspace-events Ex` buys, and why
        // the config is `Ex` and not `Kx`. `K` would publish the event name to a per-key channel
        // instead, and this listener would never fire at all (ADR-003).
        String key = new String(message.getBody(), StandardCharsets.UTF_8);

        String holdToken = HoldKeys.tokenOf(key);
        if (holdToken == null) {
            return; // Another module's key. The overwhelmingly common case.
        }

        try {
            if (holds.reclaimExpired(holdToken)) {
                log.debug("Reclaimed hold {} on its timer", holdToken);
            }
        } catch (RuntimeException failed) {
            // Nothing is lost: the hold stays ACTIVE and past its expiry, which is precisely what
            // the sweeper selects for. Rethrowing would only kill this replica's subscription and
            // cost every *subsequent* hold its fast path.
            log.warn("Could not reclaim hold {} on expiry; leaving it to the sweeper", holdToken, failed);
        }
    }
}
