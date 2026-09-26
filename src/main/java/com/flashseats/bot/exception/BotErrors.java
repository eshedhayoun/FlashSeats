package com.flashseats.bot.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/** The refusals {@code bot} raises to a client. */
public final class BotErrors {

    private BotErrors() {}

    /**
     * The challenge scored this visitor below the threshold. Raised <strong>only</strong> on a real
     * low score. A timeout, an error, a missing token or an unconfigured provider all fail open
     * (ADR-011). The copy never mentions bots, because a false positive is a real person, and a fresh
     * token may genuinely score differently, hence {@code retryable}.
     */
    public static FlashSeatsException verificationFailed() {
        return new FlashSeatsException(
                        ErrorCode.BOT_VERIFICATION_FAILED,
                        "We could not verify this request. Please reload the page and try again.")
                .with("retryable", true);
    }
}
