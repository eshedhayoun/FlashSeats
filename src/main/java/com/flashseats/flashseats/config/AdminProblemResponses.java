package com.flashseats.flashseats.config;

import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.ProblemDetails;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Makes the admin surface answer like the rest of the API.
 *
 * <p><strong>Why this class has to exist.</strong> Every deliberate failure in every module arrives
 * at {@code GlobalExceptionHandler} as a {@code FlashSeatsException} carrying an {@link ErrorCode},
 * and comes back as RFC 7807 with a {@code code} the client can switch on. Authentication and
 * authorisation failures do not: Spring Security throws them inside the filter chain, which runs
 * before {@code DispatcherServlet} ever dispatches, so no {@code @RestControllerAdvice} can see
 * them. The result was Boot's stock error body — no {@code code}, no {@code type}, no
 * {@code traceId} — on the only endpoints in the system that can pause a sale.
 *
 * <p>That was tolerable while the admin surface was one pre-warm endpoint nobody scripted against.
 * It is not tolerable now that an operator tool has to tell "your credentials are wrong" apart from
 * "this order has no dead letter", and global standards §1 says every problem carries a code without
 * carving out an exception.
 *
 * <p>Both responses are written here rather than delegated, for the same reason
 * {@code RateLimitFilter} writes its own: a filter runs where the exception handlers cannot reach.
 */
@Component
public class AdminProblemResponses {

    private static final String REALM = "flashseats";

    private final ObjectMapper json;

    public AdminProblemResponses(ObjectMapper json) {
        this.json = json;
    }

    /** No credentials, or credentials that did not verify. */
    public AuthenticationEntryPoint entryPoint() {
        return (request, response, authException) -> {
            // Replacing BasicAuthenticationEntryPoint means replacing the challenge it sent. RFC 7235
            // says a 401 MUST carry WWW-Authenticate, and without it a client has been refused with
            // no statement of how to authenticate at all.
            response.setHeader("WWW-Authenticate", "Basic realm=\"" + REALM + "\", charset=\"UTF-8\"");
            write(
                    response,
                    ErrorCode.ADMIN_AUTH_REQUIRED,
                    "Operator credentials are required for this endpoint.");
        };
    }

    /** Verified, but without {@code ROLE_ADMIN}. */
    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, deniedException) ->
                write(
                        response,
                        ErrorCode.ADMIN_FORBIDDEN,
                        "These credentials do not grant operator access.");
    }

    private void write(HttpServletResponse response, ErrorCode code, String detail)
            throws java.io.IOException {
        ProblemDetail problem = ProblemDetails.of(code, detail);
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), problem);
    }
}
