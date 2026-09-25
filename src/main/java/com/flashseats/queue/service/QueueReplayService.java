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
 * Persists the low-frequency queue frames needed to bridge an SSE reconnect.
 *
 * <p>Position frames remain live-only: the controller sends the current position immediately after
 * replay, so retaining every two-second update would turn the replay log into an unbounded copy of
 * the queue. Promotion, availability, and terminal frames are retained briefly and are published
 * through the existing Redis Pub/Sub fan-out.
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

    public QueueChannelMessage publish(long eventId, QueueChannelMessage message) {
        Long sequence = redis.opsForValue().increment(QueueKeys.replaySequence(eventId));
        if (sequence == null) {
            throw new IllegalStateException("Redis did not return a replay sequence");
        }

        QueueChannelMessage persisted = message.withId(sequence);
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

    public void publishAndFanOut(long eventId, QueueChannelMessage message) {
        QueueChannelMessage persisted = publish(eventId, message);
        try {
            redis.convertAndSend(QueueKeys.events(eventId), json.writeValueAsString(persisted));
        } catch (Exception failure) {
            throw new IllegalStateException("Could not publish queue frame", failure);
        }
    }

    public List<QueueChannelMessage> after(long eventId, String lastEventId) {
        long parsed;
        try {
            parsed = Long.parseLong(lastEventId);
        } catch (NumberFormatException invalid) {
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
