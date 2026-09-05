package com.flashseats.shared.time;

import java.time.Clock;
import java.time.Instant;

/**
 * Reads the expiry field carried inside a signed token.
 *
 * <p>One place, because both token families do exactly this — {@code queue}'s pass and admission
 * (ADR-020) and {@code order}'s receipt (ADR-039) — and the two had identical private copies. The
 * value being parsed came off the wire, so the rule that a malformed one is simply <em>not valid</em>
 * rather than an exception is a decision worth making once: a second implementation that let a
 * {@link NumberFormatException} escape would turn a tampered token into a {@code 500}.
 *
 * <p>A static helper rather than a bean because it holds nothing; both callers already have the
 * {@link Clock}.
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
