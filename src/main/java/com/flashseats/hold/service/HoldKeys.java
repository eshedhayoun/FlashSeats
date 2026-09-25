package com.flashseats.hold.service;

/**
 * The only Redis key this module owns. {@code hold:{token}} is an <strong>expiry timer and nothing
 * else</strong>: {@code ticket_holds} is the authority, and if every key vanished the sweeper would
 * simply reclaim seconds later (ADR-019).
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
