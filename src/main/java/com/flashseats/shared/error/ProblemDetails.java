package com.flashseats.shared.error;

import com.flashseats.shared.web.TraceIdFilter;
import java.net.URI;
import java.util.Map;
import org.springframework.http.ProblemDetail;

/**
 * Builds RFC 7807 {@code application/problem+json} responses in the one shape the whole API uses
 * (global standards §1).
 *
 * <p>There is deliberately <strong>no {@code ApiResponse<T>} envelope</strong> (ADR-021): an
 * envelope decouples the payload from the status code, breaks caching and conditional requests, and
 * forces a second unwrap on every client. Success responses are the resource itself.
 *
 * <p>{@code code} and {@code traceId} appear on every problem; the remaining extension members
 * appear only where the failure supplies them.
 */
public final class ProblemDetails {

    private ProblemDetails() {}

    public static ProblemDetail from(FlashSeatsException ex) {
        ProblemDetail problem = of(ex.code(), ex.getMessage());
        ex.extensions().forEach(problem::setProperty);
        return problem;
    }

    public static ProblemDetail of(ErrorCode code, String detail) {
        return of(code, detail, Map.of());
    }

    public static ProblemDetail of(ErrorCode code, String detail, Map<String, Object> extensions) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(code.status(), detail);
        problem.setType(URI.create(code.type()));
        problem.setTitle(titleFor(code));
        problem.setProperty("code", code.name());
        problem.setProperty("traceId", TraceIdFilter.current());
        extensions.forEach(problem::setProperty);
        return problem;
    }

    /** {@code HOLD_EXPIRED} to {@code "Hold expired"} — human-facing, never parsed by a client. */
    private static String titleFor(ErrorCode code) {
        String words = code.name().toLowerCase().replace('_', ' ');
        return Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
