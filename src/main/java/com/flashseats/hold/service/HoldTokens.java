package com.flashseats.hold.service;

import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * Generates hold tokens.
 *
 * <p>The token is a bearer reference that appears in URLs, so it is drawn from {@link SecureRandom}
 * rather than a sequence — a guessable token would let anyone probe other buyers' reservations. Every
 * endpoint that accepts one <em>also</em> checks session ownership, so this is defence in depth, not
 * the only guard.
 */
public final class HoldTokens {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int BYTES = 16;

    private HoldTokens() {}

    /** e.g. {@code hld_9f8b2c1a4d3e2f10b98a4c7d1e6f0a35}. */
    public static String generate() {
        byte[] bytes = new byte[BYTES];
        RANDOM.nextBytes(bytes);
        return "hld_" + HexFormat.of().formatHex(bytes);
    }
}
