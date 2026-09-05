package com.flashseats.queue.service;

import tools.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
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

    /**
     * The events this replica is actually holding connections for.
     *
     * <p>Drives {@link QueueBroadcaster}, which used to sweep <em>open</em> events instead — so the
     * moment a sale closed it stopped sweeping the very connections that most needed telling
     * (ADR-036).
     */
    public Set<Long> watchedEventIds() {
        return connections.values().stream().map(Connection::eventId).collect(Collectors.toSet());
    }

    /**
     * Delivers a final frame to every local watcher and closes the stream.
     *
     * <p>Completing is what makes a terminal frame terminal: the connection leaves the registry, so
     * the next sweep does not find it and send the same news again every two seconds.
     *
     * <p>Removal is keyed on <em>this</em> connection, not just the session id. A buyer reconnecting
     * in the same instant would otherwise have their fresh emitter evicted by the sweep that was
     * closing their old one, leaving them holding a socket nothing will ever write to.
     */
    public void closeAll(long eventId, String eventName, Object data) {
        for (Map.Entry<String, Connection> entry : Map.copyOf(connections).entrySet()) {
            if (entry.getValue().eventId() != eventId) {
                continue;
            }
            send(entry.getKey(), eventName, data);
            if (connections.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().emitter().complete();
            }
        }
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

        // An unknown estimate is null, not a -1 sentinel. QueueStatusResponse and FE_SPEC §4 both
        // use null, and a client that forgot to translate the sentinel would render "-1 seconds".
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("position", displayed);
        frame.put("aheadOfYou", Math.max(0, displayed - 1));
        frame.put("estWaitSeconds", estWaitSeconds);

        return send(sessionId, "position-update", frame);
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
