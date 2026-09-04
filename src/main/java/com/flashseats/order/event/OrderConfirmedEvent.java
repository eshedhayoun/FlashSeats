package com.flashseats.order.event;

import java.time.Instant;

/**
 * Published <em>inside</em> the order transaction, delivered <em>after</em> it commits.
 *
 * <p>That split is the point: everything that cannot participate in a SQL transaction — Redis
 * cleanup, revoking the buyer's admission — hangs off this event rather than running inline. Those
 * side effects are best-effort and safe to lose; if none of them run, the system is still correct
 * (ADR-023).
 */
public record OrderConfirmedEvent(
        String orderNumber, String holdToken, String userSessionId, long eventId, Instant at) {}
