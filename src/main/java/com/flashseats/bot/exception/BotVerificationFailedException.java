package com.flashseats.bot.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/**
 * The challenge provider scored this visitor below the threshold.
 *
 * <p>Reached <strong>only</strong> on a real low score — never on a timeout, an error, a missing
 * token or an unconfigured provider, all of which fail open (ADR-011).
 *
 * <p>The copy says nothing about bots. A false positive here is a real person being told they are
 * not one, which is both insulting and useless: there is no action a human can take in response to
 * "you appear to be automated". `retryable` is true because a fresh token genuinely may score
 * differently, and because the alternative is a dead end for the humans this occasionally catches.
 */
public class BotVerificationFailedException extends FlashSeatsException {

    public BotVerificationFailedException() {
        super(
                ErrorCode.BOT_VERIFICATION_FAILED,
                "We could not verify this request. Please reload the page and try again.");
        with("retryable", true);
    }
}
