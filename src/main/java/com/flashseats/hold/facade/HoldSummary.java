package com.flashseats.hold.facade;

import java.time.Instant;

/**
 * A hold as other modules see it — a record, never the JPA entity.
 *
 * <p>Handing an entity across a boundary would leak a lazy-loading proxy and a persistence mapping
 * into a module that is supposed to know neither (global standards §5).
 *
 * <p>There is deliberately no {@code status} field. Every facade method that returns a
 * {@code HoldSummary} guarantees the hold was {@code ACTIVE} at that moment, and the failure modes
 * arrive as exceptions rather than as a value to branch on — so no caller has to reason about the
 * hold state machine, which belongs to this module alone.
 */
public record HoldSummary(
        String holdToken,
        String userSessionId,
        long eventId,
        long tierId,
        int quantity,
        Instant expiresAt,
        Instant createdAt) {}
