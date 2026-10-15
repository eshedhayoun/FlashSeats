package com.flashseats.hold.service;

import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

/**
 * Turns a {@code hold:{token}} key expiring into an immediate reclaim. It exists for
 * <strong>latency</strong> only. {@link HoldReconciliationSweeper} is the guarantee, because keyspace
 * notifications are at-most-once pub/sub.
 *
 * <p>Every replica receives every expiry, and that is why no coordination is needed: all call
 * {@link HoldService#reclaimExpired} and the settle-once claim lets exactly one win. Restoring stock
 * here would restore it three times. The channel carries every expiring key in the database, so the
 * prefix check comes first.
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
