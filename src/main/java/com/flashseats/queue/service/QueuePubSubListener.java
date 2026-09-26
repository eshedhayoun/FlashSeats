package com.flashseats.queue.service;

import java.nio.charset.StandardCharsets;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Delivers frames published by <em>any</em> replica to the connections held by <em>this</em> one.
 * Without it the waiting room works on one instance and drops about two-thirds of promotions on
 * three (ADR-007).
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
                if ("sale-closed".equals(frame.type())) {
                    emitters.closeAll(eventIdOf(channel), frame.type(), frame.data(), frame.id());
                } else {
                    emitters.broadcast(eventIdOf(channel), frame.type(), frame.data(), frame.id());
                }
            } else {
                emitters.send(frame.sessionId(), frame.type(), frame.data(), frame.id());
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
