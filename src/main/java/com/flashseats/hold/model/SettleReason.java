package com.flashseats.hold.model;

/** Why a hold left {@code ACTIVE}. Audit only — the status is what the system acts on. */
public enum SettleReason {
    /** Became a confirmed order. */
    CONSUMED,
    /** The buyer cancelled it explicitly. */
    USER_CANCEL,
    /** Reclaimed by the reconciliation sweeper after its window passed. */
    SWEEPER,
    /** Reclaimed by the Redis keyspace expiry listener. Unused until Phase 2. */
    TTL
}
