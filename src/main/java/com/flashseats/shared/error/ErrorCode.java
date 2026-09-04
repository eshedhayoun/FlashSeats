package com.flashseats.shared.error;

import org.springframework.http.HttpStatus;

/**
 * The canonical error-code registry (global standards §2).
 *
 * <p>These values are <strong>stable API contract</strong> — the SPA switches on {@code code}, never
 * on {@code detail} or status alone. Renaming one is a breaking change.
 *
 * <p>Each constant carries its HTTP status, and the RFC 7807 {@code type} URI is derived from the
 * constant name rather than hand-written, so a typo cannot silently disagree with the registry.
 */
public enum ErrorCode {

    // --- shared -------------------------------------------------------------
    /** Malformed or invalid input. Carries a {@code violations} array. */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    /** Anything unhandled. Carries a traceId and never any internal detail. */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),

    // --- bot ----------------------------------------------------------------
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    BOT_VERIFICATION_FAILED(HttpStatus.FORBIDDEN),
    IP_BLOCKED(HttpStatus.FORBIDDEN),
    SESSION_INVALID(HttpStatus.UNAUTHORIZED),

    // --- catalog ------------------------------------------------------------
    EVENT_NOT_FOUND(HttpStatus.NOT_FOUND),
    TIER_NOT_FOUND(HttpStatus.NOT_FOUND),
    SALE_NOT_OPEN(HttpStatus.CONFLICT),
    SALE_CLOSED(HttpStatus.CONFLICT),
    /**
     * The stock counter is <strong>missing</strong> — a fault, never "sold out" (ADR-004).
     * Rendering this as sold out would tell thousands of buyers the sale ended when it had not.
     */
    INVENTORY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    PREWARM_WINDOW_CLOSED(HttpStatus.CONFLICT),

    // --- queue --------------------------------------------------------------
    NOT_IN_QUEUE(HttpStatus.NOT_FOUND),
    QUEUE_PASS_INVALID(HttpStatus.UNAUTHORIZED),
    QUEUE_PASS_EXPIRED(HttpStatus.GONE),
    ADMISSION_REQUIRED(HttpStatus.UNAUTHORIZED),
    ADMISSION_EXPIRED(HttpStatus.GONE),
    QUEUE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    SALE_EXHAUSTED(HttpStatus.CONFLICT),

    // --- hold ---------------------------------------------------------------
    INSUFFICIENT_STOCK(HttpStatus.CONFLICT),
    HOLD_NOT_FOUND(HttpStatus.NOT_FOUND),
    HOLD_EXPIRED(HttpStatus.GONE),
    HOLD_ALREADY_SETTLED(HttpStatus.CONFLICT),
    HOLD_LIMIT_EXCEEDED(HttpStatus.CONFLICT),
    QUANTITY_EXCEEDS_LIMIT(HttpStatus.UNPROCESSABLE_CONTENT),

    // --- payment ------------------------------------------------------------
    PAYMENT_DECLINED(HttpStatus.PAYMENT_REQUIRED),
    PAYMENT_ATTEMPTS_EXHAUSTED(HttpStatus.PAYMENT_REQUIRED),
    PAYMENT_ACTION_REQUIRED(HttpStatus.PAYMENT_REQUIRED),
    PAYMENT_GATEWAY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    DUPLICATE_PAYMENT(HttpStatus.CONFLICT),
    WEBHOOK_SIGNATURE_INVALID(HttpStatus.BAD_REQUEST),

    // --- order --------------------------------------------------------------
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND),
    ORDER_ALREADY_CONFIRMED(HttpStatus.CONFLICT),
    CHECKOUT_WINDOW_CLOSED(HttpStatus.CONFLICT),
    /**
     * Too little of the reservation remains to start a charge that could finish (ADR-030). Added to
     * the registry during implementation: the behaviour was specified but had no code of its own.
     */
    INSUFFICIENT_TIME_REMAINING(HttpStatus.CONFLICT),
    /**
     * The charge settled but the seats could not be delivered, so it was refunded automatically
     * (ADR-012). Distinct from {@code HOLD_EXPIRED}, whose promise is that nothing was charged —
     * here something was, and the buyer must be told the truth. Added to the registry during
     * implementation.
     */
    ORDER_REFUNDED(HttpStatus.CONFLICT),

    // --- notification -------------------------------------------------------
    NOTIFICATION_LOG_NOT_FOUND(HttpStatus.NOT_FOUND);

    private static final String TYPE_PREFIX = "https://flashseats.dev/problems/";

    private final HttpStatus status;
    private final String type;

    ErrorCode(HttpStatus status) {
        this.status = status;
        this.type = TYPE_PREFIX + name().toLowerCase().replace('_', '-');
    }

    public HttpStatus status() {
        return status;
    }

    /** RFC 7807 {@code type} URI, e.g. {@code .../problems/hold-expired}. */
    public String type() {
        return type;
    }
}
