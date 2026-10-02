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
 * Keeps the recent <strong>broadcast</strong> frames so an SSE reconnect can be caught up, and
 * publishes every frame through the Pub/Sub fan-out (ADR-007).
 *
 * <p>Only broadcasts are retained. The one session-targeted frame carries a single-use
 * {@code passToken}, and this log outlives the sale (ADR-058). A promoted buyer's reconnect instead
 * re-reads the live pass ({@link QueueBroadcaster#connect}). Position frames are live-only too:
 * {@code connect} sends the current position.
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

    /**
     * Publishes a broadcast frame unless it repeats the last one announced under {@code announcedKey}
     * (ADR-076). Every replica watching a sale sees the same change. {@code SET ... GET} swaps this
     * frame in and returns what was announced before, atomically, so one of them announces it and the
     * rest find it said. Each replica comparing against its own memory would deliver every change to
     * every stream once per replica, retain it as many times, and miss a change back to a state that
     * replica last announced itself, since its streams have heard the others since.
     */
    public void publishIfChanged(long eventId, String announcedKey, QueueChannelMessage message) {
        String content = encode(message);
        String previous = redis.opsForValue().setGet(announcedKey, content, retention());
        if (!content.equals(previous)) {
            publishOrForget(eventId, announcedKey, message);
        }
    }

    /**
     * Publishes a frame that should be said once per sale, from whichever replica claims it first
     * (ADR-076).
     *
     * @return false if another replica already announced it, and this one published nothing
     */
    public boolean publishOnce(long eventId, String announcedKey, QueueChannelMessage message) {
        if (!Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(announcedKey, "1", retention()))) {
            return false;
        }
        publishOrForget(eventId, announcedKey, message);
        return true;
    }

    /**
     * The claim goes if the frame did not: a key saying "announced" over a frame nobody received would
     * stop every replica from ever announcing it (ADR-038's rule). The next sweep tries again.
     */
    private void publishOrForget(long eventId, String announcedKey, QueueChannelMessage message) {
        try {
            publishAndFanOut(eventId, message);
        } catch (RuntimeException failed) {
            try {
                redis.delete(announcedKey);
            } catch (RuntimeException alsoFailed) {
                failed.addSuppressed(alsoFailed);
            }
            throw failed;
        }
    }

    private Duration retention() {
        return Duration.ofSeconds(properties.getKeyRetentionAfterSaleSeconds());
    }

    private String encode(QueueChannelMessage message) {
        try {
            return json.writeValueAsString(message);
        } catch (Exception failure) {
            throw new IllegalStateException("Could not encode queue frame", failure);
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
            redis.expire(QueueKeys.replay(eventId), retention());
            redis.expire(QueueKeys.replaySequence(eventId), retention());
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
