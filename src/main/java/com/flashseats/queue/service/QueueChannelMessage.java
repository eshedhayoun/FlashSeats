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
 */
public record QueueChannelMessage(String type, String sessionId, Map<String, Object> data) {

    public static QueueChannelMessage toSession(String type, String sessionId, Map<String, Object> data) {
        return new QueueChannelMessage(type, sessionId, data);
    }

    public static QueueChannelMessage toAll(String type, Map<String, Object> data) {
        return new QueueChannelMessage(type, null, data);
    }

    public boolean isBroadcast() {
        return sessionId == null;
    }
}
