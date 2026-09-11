package com.flashseats.hold.model;

/** Why a hold left {@code ACTIVE}. Audit only — the status is what the system acts on. */
public enum SettleReason {
    /** Became a confirmed order. */
    CONSUMED,
    /** The buyer cancelled it explicitly. */
    USER_CANCEL,

    /**
     * A checkout handed the seats back without charging — a gateway outage, or too little time.
     *
     * <p>Distinct from {@code USER_CANCEL} because the ledger is where anyone goes to find out what
     * happened to a reservation, and "the buyer cancelled" is a different story from "we gave up".
     */
    ORDER_ABORT,
    /** Reclaimed by the reconciliation sweeper after its window passed. */
    SWEEPER,
    /** Reclaimed by the Redis keyspace expiry listener. Unused until Phase 2. */
    TTL
}
