package com.flashseats.hold.facade;

/**
 * Why another module is handing seats back.
 *
 * <p>An enum rather than the free-form {@code String} this used to be, because the string was
 * accepted and then thrown away — every release was recorded as {@code USER_CANCEL} regardless of
 * what actually happened. A settle reason is the only record of why a hold ended, so a ledger that
 * says "the buyer cancelled" about a checkout the system abandoned is not merely imprecise, it is
 * wrong in the one place someone would go to find out.
 *
 * <p>Deliberately a small, closed set: {@code hold} owns its own {@code SettleReason}, which is a
 * {@code model} type and cannot cross a module boundary. This is the public half of that mapping.
 */
public enum HoldReleaseReason {

    /** The buyer asked for their seats back. Their place in the sale is unaffected (ADR-020). */
    USER_CANCEL,

    /**
     * A checkout gave up without charging — a gateway outage, or too little time left to finish.
     *
     * <p>Note that {@code order} does <strong>not</strong> use this on a decline. A declined card
     * keeps the hold, because the buyer was told they could try another one.
     */
    ORDER_ABORT
}
