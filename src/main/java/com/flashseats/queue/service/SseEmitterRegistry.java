package com.flashseats.queue.service;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
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
        index(sessionId, eventId);

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
        closeAll(eventId, eventName, data, null);
    }

    public void closeAll(long eventId, String eventName, Object data, Long frameId) {
        for (String sessionId : sessionsWatching(eventId)) {
            Connection connection = connections.get(sessionId);
            if (connection == null || connection.eventId() != eventId) {
                continue;
            }
            send(sessionId, eventName, data, frameId);
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

    /**
     * A live frame, deliberately carrying <strong>no</strong> {@code id}.
     *
     * <p>The SSE specification leaves a client's last-event-id untouched by an event with no
     * {@code id} field, so a position update does not overwrite the sequence a reconnect must send
     * back. That is the whole mechanism: only replayable frames are numbered
     * ({@link QueueReplayService}), everything else is derived from live state on connect, and the
     * {@code Last-Event-ID} a browser returns is therefore always a sequence the replay log knows.
     *
     * <p>An earlier cut gave these frames a per-connection {@code "local-N"} id. Positions arrive
     * every two seconds, so a reconnect almost always quoted one — and the replay log, which parses
     * the id as a number, answered every one of them with nothing. The feature could not fire.
     */
    public boolean send(String sessionId, String eventName, Object data) {
        return sendWithId(sessionId, eventName, data, null);
    }

    /** A replayable frame, identified by its position in the event's replay log. */
    public boolean send(String sessionId, String eventName, Object data, Long sequence) {
        return sendWithId(sessionId, eventName, data, sequence);
    }

    private boolean sendWithId(String sessionId, String eventName, Object data, Long sequence) {
        Connection connection = connections.get(sessionId);
        if (connection == null) {
            return false;
        }
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

    public void broadcast(long eventId, String eventName, Object data, Long sequence) {
        sessionsWatching(eventId).forEach(sessionId -> send(sessionId, eventName, data, sequence));
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

    /**
     * Both halves of the per-event index go through {@code compute}, so they hold the same per-key
     * lock.
     *
     * <p>Read and written separately, they raced: the last session of an event unindexing would see
     * the set empty and remove it, while a connect arriving in between had already added itself to
     * that same instance. The two-argument {@code remove} matches on identity and dropped it anyway,
     * leaving a live connection indexed in a map nothing iterates — no position frames and no
     * {@code sale-closed} until its one-hour timeout.
     */
    private void index(String sessionId, long eventId) {
        sessionsByEvent.compute(eventId, (ignored, sessions) -> {
            Set<String> live = sessions == null ? ConcurrentHashMap.newKeySet() : sessions;
            live.add(sessionId);
            return live;
        });
    }

    private void unindex(String sessionId, long eventId) {
        sessionsByEvent.compute(eventId, (ignored, sessions) -> {
            if (sessions == null) {
                return null;
            }
            sessions.remove(sessionId);
            return sessions.isEmpty() ? null : sessions;
        });
    }

    /**
     * One browser's stream, plus the state needed to keep its frames coherent.
     *
     * <p>The clamp is an atomic rather than guarded by a lock. On JDK 21 a virtual thread that
     * blocks inside {@code synchronized} pins its carrier, and under a flash-sale spike that presents
     * as a throughput collapse which looks like a Redis outage (global standards §7). Lock-free is
     * simpler here anyway.
     */
    private record Connection(long eventId, SseEmitter emitter, AtomicInteger lastPosition) {

        Connection(long eventId, SseEmitter emitter) {
            this(eventId, emitter, new AtomicInteger(Integer.MAX_VALUE));
        }

        int clampPosition(int incoming) {
            return lastPosition.updateAndGet(previous -> Math.min(previous, incoming));
        }
    }
}
