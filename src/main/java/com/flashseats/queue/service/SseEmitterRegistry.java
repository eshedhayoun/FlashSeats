package com.flashseats.queue.service;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The live SSE connections held by <strong>this replica</strong>.
 *
 * <p>An emitter is the one piece of state a stateless application cannot avoid keeping in memory,
 * and it is the reason promotions fan out over Redis Pub/Sub: the promotion worker runs on one
 * replica while a given buyer's connection lives in another's heap. Delivering only to local
 * emitters is correct precisely <em>because</em> every replica subscribes and does the same
 * (ADR-007).
 *
 * <p>Positions are clamped <strong>monotonic non-increasing</strong> per connection. A raw rank can
 * jump backwards when entries ahead are removed, and a queue position that goes <em>up</em> reads as
 * a broken system even when nothing is wrong.
 */
@Component
public class SseEmitterRegistry {

    private static final Logger log = LoggerFactory.getLogger(SseEmitterRegistry.class);

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final ObjectMapper json;

    public SseEmitterRegistry(ObjectMapper json) {
        this.json = json;
    }

    public SseEmitter register(String sessionId, long eventId, long timeoutMillis) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        Connection connection = new Connection(eventId, emitter);

        // Replace any previous connection for this session — a reconnect must not leave the old one
        // holding a socket that will never be written to again.
        Connection previous = connections.put(sessionId, connection);
        if (previous != null) {
            previous.emitter().complete();
        }

        emitter.onCompletion(() -> connections.remove(sessionId, connection));
        emitter.onTimeout(() -> connections.remove(sessionId, connection));
        emitter.onError(error -> connections.remove(sessionId, connection));

        return emitter;
    }

    public Set<String> sessionsWatching(long eventId) {
        return connections.entrySet().stream()
                .filter(entry -> entry.getValue().eventId() == eventId)
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    public boolean isLocal(String sessionId) {
        return connections.containsKey(sessionId);
    }

    /**
     * Sends a position update, clamped so the number never rises.
     *
     * @return false if the connection is gone
     */
    public boolean sendPosition(String sessionId, int position, Integer estWaitSeconds) {
        Connection connection = connections.get(sessionId);
        if (connection == null) {
            return false;
        }
        int displayed = connection.clampPosition(position);
        return send(
                sessionId,
                "position-update",
                Map.of(
                        "position", displayed,
                        "aheadOfYou", Math.max(0, displayed - 1),
                        "estWaitSeconds", estWaitSeconds == null ? -1 : estWaitSeconds));
    }

    public boolean send(String sessionId, String eventName, Object data) {
        Connection connection = connections.get(sessionId);
        if (connection == null) {
            return false;
        }
        try {
            connection
                    .emitter()
                    .send(SseEmitter.event()
                            .id(Long.toString(connection.nextId()))
                            .name(eventName)
                            .data(json.writeValueAsString(data)));
            return true;
        } catch (IOException | IllegalStateException disconnected) {
            // Routine: browsers close streams constantly. Not worth a stack trace.
            log.debug("Dropping dead SSE connection for {}", sessionId);
            connections.remove(sessionId, connection);
            connection.emitter().complete();
            return false;
        }
    }

    /** Delivers to every local watcher of an event. Terminal frames use this. */
    public void broadcast(long eventId, String eventName, Object data) {
        sessionsWatching(eventId).forEach(sessionId -> send(sessionId, eventName, data));
    }

    /**
     * A comment frame. Keeps proxies from closing an idle stream and lets the client notice a dead
     * connection quickly.
     */
    public void heartbeat(long eventId) {
        for (String sessionId : sessionsWatching(eventId)) {
            Connection connection = connections.get(sessionId);
            if (connection == null) {
                continue;
            }
            try {
                connection.emitter().send(SseEmitter.event().comment("hb"));
            } catch (IOException | IllegalStateException disconnected) {
                connections.remove(sessionId, connection);
                connection.emitter().complete();
            }
        }
    }

    /**
     * One browser's stream, plus the state needed to keep its frames coherent.
     *
     * <p>Both fields are atomics rather than guarded by a lock. On JDK 21 a virtual thread that
     * blocks inside {@code synchronized} pins its carrier, and under a flash-sale spike that presents
     * as a throughput collapse which looks like a Redis outage (global standards §7). Lock-free is
     * simpler here anyway.
     */
    private record Connection(long eventId, SseEmitter emitter, AtomicLong ids, AtomicInteger lastPosition) {

        Connection(long eventId, SseEmitter emitter) {
            this(eventId, emitter, new AtomicLong(), new AtomicInteger(Integer.MAX_VALUE));
        }

        long nextId() {
            return ids.incrementAndGet();
        }

        int clampPosition(int incoming) {
            return lastPosition.updateAndGet(previous -> Math.min(previous, incoming));
        }
    }
}
