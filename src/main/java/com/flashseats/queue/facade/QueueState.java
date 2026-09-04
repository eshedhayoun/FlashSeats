package com.flashseats.queue.facade;

import java.time.Instant;

/**
 * A session's position in the sale, for rehydration after a reload.
 *
 * <p>{@code passToken} is included when a promotion happened while the client was away — that is how
 * a promotion survives a dead socket, a backgrounded tab, or a network handover (ADR-007).
 */
public record QueueState(
        QueuePhase phase,
        Integer position,
        Integer estWaitSeconds,
        Instant admissionExpiresAt,
        String passToken) {

    public static QueueState notJoined() {
        return new QueueState(QueuePhase.NOT_JOINED, null, null, null, null);
    }
}
