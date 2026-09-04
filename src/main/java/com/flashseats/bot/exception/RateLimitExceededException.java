package com.flashseats.bot.exception;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.FlashSeatsException;

/** Too many requests from this session or address. */
public class RateLimitExceededException extends FlashSeatsException {

    public RateLimitExceededException(int retryAfterSeconds) {
        super(ErrorCode.RATE_LIMITED, "Too many requests. Please slow down and try again shortly.");
        with("retryable", true);
        with("retryAfterSeconds", retryAfterSeconds);
    }
}
