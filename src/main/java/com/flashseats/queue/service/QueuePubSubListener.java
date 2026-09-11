package com.flashseats.queue.service;

import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Delivers queue frames published by <em>any</em> replica to the connections held by <em>this</em>
 * one.
 *
 * <p>This class is the fix for the defect that most often breaks a waiting room in production. The
 * promotion worker runs on a single replica; a buyer's {@code SseEmitter} lives wherever the load
 * balancer happened to put them. Without this fan-out the system works perfectly on one instance and
 * silently drops roughly two-thirds of promotions on three — and no single-instance test can see it
 * (ADR-007).
 */
@Slf4j
@Component
public class QueuePubSubListener implements MessageListener {

    private final SseEmitterRegistry emitters;
    private final ObjectMapper json;

    public QueuePubSubListener(SseEmitterRegistry emitters, ObjectMapper json) {
        this.emitters = emitters;
        this.json = json;
    }

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
        try {
            QueueChannelMessage frame =
                    json.readValue(message.getBody(), QueueChannelMessage.class);

            if (frame.isBroadcast()) {
                emitters.broadcast(eventIdOf(channel), frame.type(), frame.data());
            } else {
                emitters.send(frame.sessionId(), frame.type(), frame.data());
            }
        } catch (Exception malformed) {
            log.warn("Ignoring unreadable queue frame on {}", channel, malformed);
        }
    }

    /** {@code queue:events:10024} to {@code 10024}. */
    private long eventIdOf(String channel) {
        return Long.parseLong(channel.substring(channel.lastIndexOf(':') + 1));
    }
}
