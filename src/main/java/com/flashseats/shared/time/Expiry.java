package com.flashseats.shared.time;

import java.time.Clock;
import java.time.Instant;

/**
 * Reads the expiry field inside a signed token, for both token families. A malformed value is
 * simply not valid, never a {@link NumberFormatException} that turns a tampered token into a
 * {@code 500}.
 */
public final class Expiry {

    private Expiry() {}

    /**
     * True when {@code expiryEpochSecond} parses and is still in the future.
     *
     * <p>Never throws. A token whose expiry field has been tampered with is an ordinary outcome —
     * it fails verification like any other bad token.
     */
    public static boolean notPassed(Clock clock, String expiryEpochSecond) {
        try {
            return clock.instant().isBefore(Instant.ofEpochSecond(Long.parseLong(expiryEpochSecond)));
        } catch (NumberFormatException tampered) {
            return false;
        }
    }
}
