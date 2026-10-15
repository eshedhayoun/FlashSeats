package com.flashseats.queue.service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
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
 * The live SSE connections held by <strong>this replica</strong>. It delivers only locally, which is
 * correct because every replica subscribes to the Pub/Sub fan-out (ADR-007). Positions are clamped
 * monotonic non-increasing per connection, because a position that goes up reads as broken.
 *
 * <p><strong>One stream per tab, not per session</strong> (ADR-070). Streams are kept per event and
 * per session, and a session may hold several: one buyer with two tabs on a sale, or queued in two
 * sales at once — a supported state (FE_SPEC rule 5). Keying by session alone made every new tab
 * complete the previous one, so two tabs kicked each other off on every reconnect, and a promotion for
 * one sale was delivered to whichever sale's stream the session happened to hold last.
 */
@Slf4j
@Component
public class SseEmitterRegistry {

    /**
     * Streams one session may hold for one event before its oldest is closed. Enough for any honest
     * number of tabs; a client opening streams in a loop only ever churns its own.
     */
    static final int MAX_STREAMS_PER_SESSION = 5;

    /** eventId → sessionId → that session's live streams for the event. */
    private final Map<Long, Map<String, Set<Connection>>> streams = new ConcurrentHashMap<>();
    private final AtomicLong opened = new AtomicLong();
    private final ObjectMapper json;

    public SseEmitterRegistry(ObjectMapper json) {
        this.json = json;
    }

    /**
     * Opens one tab's stream. A session already holding {@link #MAX_STREAMS_PER_SESSION} streams for
     * this event has its oldest closed, so a reconnect loop cannot accumulate dead sockets.
     */
    Connection open(String sessionId, long eventId, long timeoutMillis) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        Connection connection = new Connection(sessionId, eventId, emitter, opened.incrementAndGet());
        List<Connection> evicted = new ArrayList<>();

        streams.compute(eventId, (ignored, sessions) -> {
            Map<String, Set<Connection>> live = sessions == null ? new ConcurrentHashMap<>() : sessions;
            Set<Connection> mine = live.computeIfAbsent(sessionId, id -> ConcurrentHashMap.newKeySet());
            mine.add(connection);
            while (mine.size() > MAX_STREAMS_PER_SESSION) {
                Connection oldest = mine.stream().min(Comparator.comparingLong(Connection::sequence)).orElseThrow();
                mine.remove(oldest);
                evicted.add(oldest);
            }
            return live;
        });
        // Completed outside compute: completion runs the emitter's callbacks, which remove the
        // connection — a recursive update of the same map key, which ConcurrentHashMap refuses.
        evicted.forEach(stale -> stale.emitter().complete());

        emitter.onCompletion(() -> remove(connection));
        emitter.onTimeout(() -> remove(connection));
        emitter.onError(error -> remove(connection));
        return connection;
    }

    public Set<String> sessionsWatching(long eventId) {
        Map<String, Set<Connection>> sessions = streams.get(eventId);
        return sessions == null ? Set.of() : Set.copyOf(sessions.keySet());
    }

    /**
     * The events this replica holds connections for. The broadcaster sweeps these rather than open
     * events, so a closing sale still reaches its streams (ADR-036).
     */
    public Set<Long> watchedEventIds() {
        return Set.copyOf(streams.keySet());
    }

    /**
     * Delivers a final frame to every local stream of an event and closes it, so the next sweep does
     * not repeat it. Removal is keyed on the connection, so a reconnect in the same instant keeps its
     * fresh stream.
     */
    public void closeAll(long eventId, String eventName, Object data) {
        closeAll(eventId, eventName, data, null);
    }

    public void closeAll(long eventId, String eventName, Object data, Long frameId) {
        for (Connection connection : connectionsOf(eventId)) {
            send(connection, eventName, data, frameId);
            if (remove(connection)) {
                connection.emitter().complete();
            }
        }
    }

    /**
     * Sends a position update to every stream this session holds for the event, clamped per stream so
     * the number a tab shows never rises.
     *
     * @return false if the session holds no live stream for the event
     */
    public boolean sendPosition(String sessionId, long eventId, int position, Integer estWaitSeconds) {
        boolean delivered = false;
        for (Connection connection : connectionsOf(sessionId, eventId)) {
            delivered |= sendPosition(connection, position, estWaitSeconds);
        }
        return delivered;
    }

    boolean sendPosition(Connection connection, int position, Integer estWaitSeconds) {
        int displayed = connection.clampPosition(position);

        // An unknown estimate is null, not a -1 sentinel. QueueStatusResponse and FE_SPEC §4 both
        // use null, and a client that forgot to translate the sentinel would render "-1 seconds".
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("position", displayed);
        frame.put("aheadOfYou", Math.max(0, displayed - 1));
        frame.put("estWaitSeconds", estWaitSeconds);
        return send(connection, "position-update", frame, null);
    }

    /**
     * A live frame to every stream this session holds for the event, deliberately carrying
     * <strong>no</strong> {@code id}. The SSE spec leaves a client's last-event-id untouched by such a
     * frame, so the {@code Last-Event-ID} a reconnect sends is always a sequence the replay log minted
     * ({@link QueueReplayService}, ADR-058).
     */
    public boolean send(String sessionId, long eventId, String eventName, Object data) {
        return send(sessionId, eventId, eventName, data, null);
    }

    /** As above, identified by its position in the event's replay log when {@code sequence} is set. */
    public boolean send(String sessionId, long eventId, String eventName, Object data, Long sequence) {
        boolean delivered = false;
        for (Connection connection : connectionsOf(sessionId, eventId)) {
            delivered |= send(connection, eventName, data, sequence);
        }
        return delivered;
    }

    boolean send(Connection connection, String eventName, Object data, Long sequence) {
        try {
            SseEmitter.SseEventBuilder frame =
                    SseEmitter.event().name(eventName).data(json.writeValueAsString(data));
            if (sequence != null) {
                frame = frame.id(Long.toString(sequence));
            }
            connection.emitter().send(frame);
            return true;
        } catch (IOException | IllegalStateException disconnected) {
            // Routine: browsers close streams constantly. Not worth a stack trace.
            log.debug("Dropping dead SSE connection for {}", connection.sessionId());
            remove(connection);
            connection.emitter().complete();
            return false;
        }
    }

    boolean comment(Connection connection, String comment) {
        try {
            connection.emitter().send(SseEmitter.event().comment(comment));
            return true;
        } catch (IOException | IllegalStateException disconnected) {
            remove(connection);
            connection.emitter().complete();
            return false;
        }
    }

    /** Delivers to every local stream of an event. Terminal frames use this. */
    public void broadcast(long eventId, String eventName, Object data) {
        broadcast(eventId, eventName, data, null);
    }

    public void broadcast(long eventId, String eventName, Object data, Long sequence) {
        connectionsOf(eventId).forEach(connection -> send(connection, eventName, data, sequence));
    }

    /**
     * A comment frame. Keeps proxies from closing an idle stream and lets the client notice a dead
     * connection quickly.
     */
    public void heartbeat(long eventId) {
        connectionsOf(eventId).forEach(connection -> comment(connection, "hb"));
    }

    private List<Connection> connectionsOf(long eventId) {
        Map<String, Set<Connection>> sessions = streams.get(eventId);
        if (sessions == null) {
            return List.of();
        }
        List<Connection> all = new ArrayList<>();
        sessions.values().forEach(all::addAll);
        return all;
    }

    private List<Connection> connectionsOf(String sessionId, long eventId) {
        Map<String, Set<Connection>> sessions = streams.get(eventId);
        Set<Connection> mine = sessions == null ? null : sessions.get(sessionId);
        return mine == null ? List.of() : List.copyOf(mine);
    }

    /**
     * Removes one connection, under the event's lock so a concurrent open cannot race the emptied
     * maps away from under it.
     *
     * @return true if this call removed it
     */
    private boolean remove(Connection connection) {
        boolean[] removed = {false};
        streams.computeIfPresent(connection.eventId(), (ignored, sessions) -> {
            Set<Connection> mine = sessions.get(connection.sessionId());
            if (mine != null) {
                removed[0] = mine.remove(connection);
                if (mine.isEmpty()) {
                    sessions.remove(connection.sessionId());
                }
            }
            return sessions.isEmpty() ? null : sessions;
        });
        return removed[0];
    }

    /**
     * One tab's stream, plus the state needed to keep its frames coherent.
     *
     * <p>The clamp is an atomic rather than guarded by a lock. On JDK 21 a virtual thread that
     * blocks inside {@code synchronized} pins its carrier, and under a flash-sale spike that presents
     * as a throughput collapse which looks like a Redis outage (global standards §7). Lock-free is
     * simpler here anyway. Identity equality on purpose: two tabs are two connections.
     */
    static final class Connection {

        private final String sessionId;
        private final long eventId;
        private final SseEmitter emitter;
        private final long sequence;
        private final AtomicInteger lastPosition = new AtomicInteger(Integer.MAX_VALUE);

        Connection(String sessionId, long eventId, SseEmitter emitter, long sequence) {
            this.sessionId = sessionId;
            this.eventId = eventId;
            this.emitter = emitter;
            this.sequence = sequence;
        }

        String sessionId() {
            return sessionId;
        }

        long eventId() {
            return eventId;
        }

        SseEmitter emitter() {
            return emitter;
        }

        long sequence() {
            return sequence;
        }

        int clampPosition(int incoming) {
            return lastPosition.updateAndGet(previous -> Math.min(previous, incoming));
        }
    }
}
