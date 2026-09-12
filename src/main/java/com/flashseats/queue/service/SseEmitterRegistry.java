package com.flashseats.queue.service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

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
@Slf4j
@Component
public class SseEmitterRegistry {

    private final Map<String, Connection> connections = new ConcurrentHashMap<>();
    private final Map<Long, Set<String>> sessionsByEvent = new ConcurrentHashMap<>();
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
            unindex(sessionId, previous.eventId());
            previous.emitter().complete();
        }
        sessionsByEvent.computeIfAbsent(eventId, ignored -> ConcurrentHashMap.newKeySet()).add(sessionId);

        emitter.onCompletion(() -> remove(sessionId, connection));
        emitter.onTimeout(() -> remove(sessionId, connection));
        emitter.onError(error -> remove(sessionId, connection));

        return emitter;
    }

    public Set<String> sessionsWatching(long eventId) {
        return Set.copyOf(sessionsByEvent.getOrDefault(eventId, Set.of()));
    }

    /**
     * The events this replica is actually holding connections for.
     *
     * <p>Drives {@link QueueBroadcaster}, which used to sweep <em>open</em> events instead — so the
     * moment a sale closed it stopped sweeping the very connections that most needed telling
     * (ADR-036).
     */
    public Set<Long> watchedEventIds() {
        return Set.copyOf(sessionsByEvent.keySet());
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
        for (String sessionId : sessionsWatching(eventId)) {
            Connection connection = connections.get(sessionId);
            if (connection == null || connection.eventId() != eventId) {
                continue;
            }
            send(sessionId, eventName, data);
            if (remove(sessionId, connection)) {
                connection.emitter().complete();
            }
        }
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
            remove(sessionId, connection);
            connection.emitter().complete();
            return false;
        }
    }

    public boolean comment(String sessionId, String comment) {
        Connection connection = connections.get(sessionId);
        if (connection == null) {
            return false;
        }
        try {
            connection.emitter().send(SseEmitter.event().comment(comment));
            return true;
        } catch (IOException | IllegalStateException disconnected) {
            remove(sessionId, connection);
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
            comment(sessionId, "hb");
        }
    }

    private boolean remove(String sessionId, Connection connection) {
        if (!connections.remove(sessionId, connection)) {
            return false;
        }
        unindex(sessionId, connection.eventId());
        return true;
    }

    private void unindex(String sessionId, long eventId) {
        Set<String> sessions = sessionsByEvent.get(eventId);
        if (sessions == null) {
            return;
        }
        sessions.remove(sessionId);
        if (sessions.isEmpty()) {
            sessionsByEvent.remove(eventId, sessions);
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
