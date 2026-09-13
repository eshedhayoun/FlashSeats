package com.flashseats.bot.model;

/**
 * Why a request was written to the audit trail.
 *
 * <p>There is no {@code ALLOWED}. A row per allowed request would be a write per request during a
 * flash sale, and the table would become unreadable for exactly the purpose it exists to serve.
 */
public enum BotOutcome {
    RATE_LIMITED,
    IP_BLOCKED,
    VERIFICATION_FAILED,

    /**
     * The challenge provider could not be reached and the request was <strong>allowed anyway</strong>
     * (ADR-011).
     *
     * <p>This is the row that matters most on this list. Failing open is the right trade — a
     * provider outage must not stop a sale — but it is indistinguishable from normal operation
     * unless it is recorded, and "our bot defence was off for three hours" is not something anyone
     * should learn afterwards from an absence.
     */
    VERIFICATION_DEGRADED
}
