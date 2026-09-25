package com.flashseats.queue.service;

import com.flashseats.queue.config.QueueProperties;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Persists the low-frequency <strong>broadcast</strong> queue frames needed to bridge an SSE
 * reconnect, and publishes every frame through the Pub/Sub fan-out (ADR-007).
 *
 * <p><strong>Only broadcast frames are retained, and that is a rule rather than an optimisation.</strong>
 * The log is one ZSET per event, shared by everyone watching that sale and outliving the sale itself
 * by {@code key-retention-after-sale-seconds}. A session-targeted frame is addressed to one buyer, and
 * the only one this system sends carries a {@code passToken} — a single-use bearer capability whose own
 * key expires in 120 s. Writing it here would turn a two-minute capability into a durable per-event
 * record sitting next to the session id it belongs to, which is the shape ADR-048 already refused for
 * {@code receiptToken}.
 *
 * <p><strong>What replaces replay for the promoted buyer</strong> is a live read. The stream endpoint
 * asks {@link QueueService#getQueueState} on connect and sends {@code queue-promoted} when the phase
 * says so, reading the pass from {@code queue:pass:&#123;e&#125;:&#123;sid&#125;} — the same idiom as
 * the {@code hold:&#123;token&#125;} timer, where the Redis hint is never the authority and the real
 * thing is re-read (ADR-048). It is also strictly better than a replay: it needs no
 * {@code Last-Event-ID}, so it works in a fresh tab or on another device, and it cannot hand back a
 * pass that has since expired.
 *
 * <p>Position frames are live-only for a different reason — the controller sends the current position
 * on connect, and retaining every two-second update would make this an unbounded copy of the queue.
 */
@Slf4j
@Component
public class QueueReplayService {

    private static final int MAX_FRAMES = 256;
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final QueueProperties properties;

    public QueueReplayService(
            StringRedisTemplate redis, ObjectMapper json, QueueProperties properties) {
        this.redis = redis;
        this.json = json;
        this.properties = properties;
    }

    /**
     * Fans a frame out to every replica, retaining it for replay only when it is a broadcast.
     *
     * <p>A session-targeted frame takes the pre-existing path: straight to the channel, unnumbered
     * and unretained.
     */
    public void publishAndFanOut(long eventId, QueueChannelMessage message) {
        QueueChannelMessage frame = message.isBroadcast() ? retain(eventId, message) : message;
        try {
            redis.convertAndSend(QueueKeys.events(eventId), json.writeValueAsString(frame));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not publish queue frame", failure);
        }
    }

    private QueueChannelMessage retain(long eventId, QueueChannelMessage message) {
        Long sequence = redis.opsForValue().increment(QueueKeys.replaySequence(eventId));
        if (sequence == null) {
            throw new IllegalStateException("Redis did not return a replay sequence");
        }

        QueueChannelMessage persisted = message.withSequence(sequence);
        try {
            String encoded = json.writeValueAsString(persisted);
            redis.opsForZSet().add(QueueKeys.replay(eventId), encoded, sequence);
            redis.opsForZSet().removeRange(QueueKeys.replay(eventId), 0, -MAX_FRAMES - 1);
            Duration retention = Duration.ofSeconds(properties.getKeyRetentionAfterSaleSeconds());
            redis.expire(QueueKeys.replay(eventId), retention);
            redis.expire(QueueKeys.replaySequence(eventId), retention);
            return persisted;
        } catch (Exception failure) {
            throw new IllegalStateException("Could not persist queue replay frame", failure);
        }
    }

    /**
     * The retained frames a reconnecting client has not seen.
     *
     * <p>An unparseable {@code Last-Event-ID} yields nothing rather than everything. It should not
     * happen — replayable frames are the only ones that carry an {@code id}, so a browser can only
     * quote a sequence this log minted — but the header is client-supplied and replaying the whole
     * window to anyone who sends a word is not a sensible reading of "I missed something".
     */
    public List<QueueChannelMessage> after(long eventId, String lastEventId) {
        long parsed;
        try {
            parsed = Long.parseLong(lastEventId.trim());
        } catch (NumberFormatException | NullPointerException invalid) {
            return List.of();
        }

        var encoded = redis.opsForZSet()
                .rangeByScore(QueueKeys.replay(eventId), parsed + 1d, Double.POSITIVE_INFINITY);
        if (encoded == null || encoded.isEmpty()) {
            return List.of();
        }

        List<QueueChannelMessage> frames = new ArrayList<>(encoded.size());
        for (String value : encoded) {
            try {
                frames.add(json.readValue(value, QueueChannelMessage.class));
            } catch (Exception malformed) {
                log.warn("Ignoring malformed queue replay frame for event {}", eventId, malformed);
            }
        }
        frames.sort(Comparator.comparing(QueueChannelMessage::id));
        return frames;
    }
}
