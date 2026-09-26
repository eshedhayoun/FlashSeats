package com.flashseats.shared.error;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base type for every exception raised to the HTTP layer. Carrying the {@link ErrorCode} lets one
 * {@link GlobalExceptionHandler} serve every module without importing their types (ADR-033).
 * {@code extensions} carries the RFC 7807 members that vary by failure (global standards §1).
 *
 * <p>Most failures come from a module's {@code <Module>Errors} factory. A subclass exists only
 * where a {@code catch} names it (ADR-057, ADR-063).
 */
public class FlashSeatsException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, Object> extensions = new LinkedHashMap<>();

    public FlashSeatsException(ErrorCode code, String detail) {
        super(detail);
        this.code = code;
    }

    public FlashSeatsException(ErrorCode code, String detail, Throwable cause) {
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
