package com.flashseats.shared.error;

import org.springframework.http.HttpStatus;

/**
 * The canonical error-code registry (global standards §2). These values are <strong>stable API
 * contract</strong>: the SPA switches on {@code code}, so renaming one is a breaking change. Each
 * constant carries its HTTP status, and the RFC 7807 {@code type} URI is derived from the name.
 *
 * <p>Reachability is checked both ways: a code is added with the path that raises it, and removed
 * only when its feature is genuinely not being built.
 */
public enum ErrorCode {

    // --- shared -------------------------------------------------------------
    /** Malformed or invalid input. Carries a {@code violations} array. */
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST),
    /** Anything unhandled. Carries a traceId and never any internal detail. */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR),
    /**
     * Back-pressure, not a fault: no database connection became free inside
     * {@code connection-timeout} (ADR-059). Carries {@code retryAfterSeconds} and a
     * {@code Retry-After} header. Checkout is find-or-create, so re-POSTing the same body is safe.
     */
    SERVICE_BUSY(HttpStatus.SERVICE_UNAVAILABLE),
    /** No endpoint or resource at this path. A caller's mistake, never a server fault (ADR-067). */
    NOT_FOUND(HttpStatus.NOT_FOUND),

    // --- bot ----------------------------------------------------------------
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS),
    /** A challenge was demanded and the token did not verify. */
    BOT_VERIFICATION_FAILED(HttpStatus.FORBIDDEN),
    /** An operator rule denies this address outright. Terminal for the caller. */
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
    /**
     * Another rebuild already holds the lock for this event. Retry, do not force — two rebuilds
     * computing from a ledger that is moving under them is how a recovery makes things worse.
     */
    STOCK_REBUILD_IN_PROGRESS(HttpStatus.SERVICE_UNAVAILABLE),
    /**
     * The charge settled, the seats could not be delivered, and the provider refused the refund. Money
     * owed to the buyer, now with a person (ADR-069). Never {@link #ORDER_REFUNDED}, which says the
     * money is back.
     */
    REFUND_FAILED(HttpStatus.CONFLICT),

    // --- queue --------------------------------------------------------------
    QUEUE_PASS_INVALID(HttpStatus.UNAUTHORIZED),
    ADMISSION_REQUIRED(HttpStatus.UNAUTHORIZED),
    ADMISSION_EXPIRED(HttpStatus.GONE),

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
    /**
     * 3-D Secure. Carries {@code clientSecret}; the client runs the challenge and then re-POSTs the
     * same checkout body — there is no resume endpoint, because that would be a second retry
     * mechanism beside find-or-create (ADR-054).
     */
    PAYMENT_ACTION_REQUIRED(HttpStatus.PAYMENT_REQUIRED),
    PAYMENT_GATEWAY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
    DUPLICATE_PAYMENT(HttpStatus.CONFLICT),
    /** Gateway-facing only: a webhook body whose signature did not verify. Never seen by a buyer. */
    WEBHOOK_SIGNATURE_INVALID(HttpStatus.BAD_REQUEST),

    // --- order --------------------------------------------------------------
    ORDER_NOT_FOUND(HttpStatus.NOT_FOUND),
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

    /**
     * The order exists and is the caller's, but it has no ticket to hand over (ADR-050).
     *
     * <p>Distinct from {@code ORDER_NOT_FOUND}, which is what an <em>unauthorised</em> caller gets
     * and must stay indistinguishable from "no such order". This one is only ever returned to
     * someone who has already proved the order is theirs, so it can afford to say why: a
     * {@code PENDING} order may have a ticket shortly, and a {@code REFUNDED} one never will. The
     * client needs to tell "wait" from "stop waiting", and a bare {@code 404} tells it neither.
     */
    TICKET_NOT_AVAILABLE(HttpStatus.CONFLICT),

    // --- admin --------------------------------------------------------------
    /**
     * No operator credentials, or the wrong ones.
     *
     * <p>Thrown from the Spring Security filter chain, which runs <strong>before</strong>
     * {@code DispatcherServlet} — so {@code GlobalExceptionHandler} never sees it and cannot supply
     * this. {@code AdminProblemResponses} wires it in at the entry point instead. Without that, the
     * admin surface was the one part of this API answering with a body that carried no {@code code}
     * at all (global standards §1).
     */
    ADMIN_AUTH_REQUIRED(HttpStatus.UNAUTHORIZED),
    /** Authenticated, but not an operator. Same filter-chain origin as the code above. */
    ADMIN_FORBIDDEN(HttpStatus.FORBIDDEN),
    /**
     * The sale is paused: nothing is reserved until an operator resumes it, and every buyer keeps their
     * place (ADR-066). Retryable, unlike {@link #SALE_CLOSED}.
     */
    SALE_PAUSED(HttpStatus.CONFLICT),
    /** Pause or resume asked for on an event that is neither published nor paused. Admin only. */
    EVENT_NOT_PAUSABLE(HttpStatus.CONFLICT),
    /**
     * A resend was asked for but the original message is gone: {@code outbox_events} keeps payloads
     * for {@code flashseats.outbox.purge-after-days} and this order is past it. {@code 410}, not
     * {@code 404}: the order and its message both existed, they have simply aged out, and a
     * {@code 404} would send an operator hunting for a typo in the order number.
     */
    NOTIFICATION_PAYLOAD_UNAVAILABLE(HttpStatus.GONE);

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
