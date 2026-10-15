package com.flashseats.app;

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
 * Makes the admin surface answer like the rest of the API. Spring Security rejects inside the
 * filter chain, before any {@code @RestControllerAdvice}, so without this the endpoints that can
 * pause a sale answered Boot's body with no {@code code} (global standards §1). Written here for the
 * same reason {@code RateLimitFilter} writes its own.
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
