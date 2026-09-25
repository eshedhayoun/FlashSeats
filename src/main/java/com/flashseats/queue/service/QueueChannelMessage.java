package com.flashseats.queue.service;

import java.util.Map;

/**
 * What travels on {@code queue:events:{eventId}}.
 *
 * <p>{@code sessionId} addresses one buyer — a promotion. A {@code null} {@code sessionId} means the
 * frame is for everyone watching the event, which is how terminal states such as
 * {@code sale-exhausted} reach the whole waiting room at once.
 *
 * <p>Every replica receives every message and delivers only to the connections in its own heap.
 * That is not wasteful, it is the mechanism: the promoter has no idea which replica is holding a
 * given browser's stream (ADR-007).
 *
 * <p>{@code id} is the replay sequence, and it is {@code null} on everything that is not retained —
 * which is every session-targeted frame. A frame with no sequence reaches the browser with no SSE
 * {@code id}, leaving its last-event-id pointing at the last thing it could actually ask to have
 * replayed.
 */
public record QueueChannelMessage(
        Long id, String type, String sessionId, Map<String, Object> data) {

    public static QueueChannelMessage toSession(String type, String sessionId, Map<String, Object> data) {
        return new QueueChannelMessage(null, type, sessionId, data);
    }

    public static QueueChannelMessage toAll(String type, Map<String, Object> data) {
        return new QueueChannelMessage(null, type, null, data);
    }

    /**
     * The one frame that carries a capability, and therefore the one that is never retained.
     *
     * <p>Built here rather than at each call site because it has two: the promoter mints a pass and
     * announces it, and the stream endpoint re-sends it to a buyer who reconnects still holding one.
     * The second is what replaces replaying it out of Redis — see {@link QueueReplayService}.
     */
    public static QueueChannelMessage promotion(
            String sessionId, String passToken, long expiresInSeconds) {
        return toSession(
                "queue-promoted",
                sessionId,
                Map.of("passToken", passToken, "expiresInSeconds", expiresInSeconds));
    }

    public boolean isBroadcast() {
        return sessionId == null;
    }

    /**
     * Stamps this frame with its position in the event's replay log.
     *
     * <p>The number is a per-event sequence, not an event id, and it becomes the SSE {@code id} the
     * browser quotes back as {@code Last-Event-ID}. Only retained frames get one — see
     * {@link QueueReplayService}.
     */
    QueueChannelMessage withSequence(long sequence) {
        return new QueueChannelMessage(sequence, type, sessionId, data);
    }
}
