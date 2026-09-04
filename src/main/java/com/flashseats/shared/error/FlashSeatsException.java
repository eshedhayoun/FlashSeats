package com.flashseats.shared.error;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base type for every exception any module raises to the HTTP layer.
 *
 * <p>Carrying the {@link ErrorCode} on the exception is what lets a <em>single</em>
 * {@link GlobalExceptionHandler} serve all nine modules without importing one module-specific type
 * (ADR-033). Global standards §1 originally asked for one {@code @RestControllerAdvice} per module
 * to stop a global handler pulling every module's exceptions into one class — a shared base type
 * satisfies that constraint with one class instead of seven.
 *
 * <p>{@code extensions} carries the RFC 7807 members that vary by failure — {@code retryable},
 * {@code attemptsRemaining}, {@code expiresAt}, {@code retryAfterSeconds}. Names and types are fixed
 * by global standards §1; the SPA switches on them.
 */
public class FlashSeatsException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> extensions = new LinkedHashMap<>();

    protected FlashSeatsException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    protected FlashSeatsException(ErrorCode code, String detail, Throwable cause) {
        super(detail, cause);
        this.code = code;
    }

    public ErrorCode code() {
        return code;
    }

    public Map<String, Object> extensions() {
        return extensions;
    }

    /**
     * Adds one RFC 7807 extension member. Intended for use at the throw site:
     * {@snippet : throw new HoldExpiredException(token).with("expiresAt", hold.expiresAt()); }
     */
    public FlashSeatsException with(String name, Object value) {
        if (value != null) {
            extensions.put(name, value);
        }
        return this;
    }
}
