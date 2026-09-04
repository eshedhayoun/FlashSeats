package com.flashseats.queue.dto;

import com.flashseats.queue.facade.QueuePhase;
import java.time.Instant;

/**
 * Where the caller stands, and — crucially — their pass if one was minted while they were away.
 *
 * <p>Returning the pass here is what makes the polling fallback equivalent to the live stream: a
 * promotion is never lost to a dead socket, a backgrounded tab or a network handover (ADR-007).
 */
public record QueueStatusResponse(
        QueuePhase phase,
        Integer position,
        Integer aheadOfYou,
        Integer estWaitSeconds,
        String passToken,
        Instant admissionExpiresAt,
        Instant serverTime) {}
