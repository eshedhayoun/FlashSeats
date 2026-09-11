package com.flashseats.hold.service;

/**
 * The only Redis key this module owns.
 *
 * <p>{@code hold:{token}} is an <strong>expiry timer and nothing else</strong>. It carries no state:
 * {@code ticket_holds} is the authority for a hold's lifecycle, and if every key here vanished the
 * table would still describe the truth — the sweeper would simply reclaim seconds later instead of
 * milliseconds. That is the whole design (ADR-019): an earlier draft kept the settle-once claim in
 * Redis, which meant consuming a hold mutated Redis inside the order's SQL transaction, and a failed
 * commit left the claim spent and the seats permanently unsellable.
 */
final class HoldKeys {

    /**
     * Every key this module writes starts here, and the expiry listener uses it to ignore everything
     * else. {@code __keyevent@0__:expired} is one channel for the <em>entire</em> database — queue
     * passes, admissions, payment guards, rate-limit buckets — so this prefix is the only thing
     * standing between the listener and every other module's expiries.
     */
    static final String TIMER_PREFIX = "hold:";

    private HoldKeys() {}

    static String timer(String holdToken) {
        return TIMER_PREFIX + holdToken;
    }

    /** {@code hold:abc123} to {@code abc123}, or null if this is not one of ours. */
    static String tokenOf(String key) {
        return key.startsWith(TIMER_PREFIX) ? key.substring(TIMER_PREFIX.length()) : null;
    }
}
