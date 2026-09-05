package com.flashseats.bot.filter;

import com.flashseats.bot.service.RateLimitService;
import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.ProblemDetails;
import com.flashseats.shared.identity.SessionId;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Applies the session and IP token buckets.
 *
 * <p>Runs after {@link SessionIdentityFilter}, so every request already has a verified identity to
 * charge against.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int RETRY_AFTER_SECONDS = 2;

    private final RateLimitService rateLimits;
    private final ObjectMapper json;

    public RateLimitFilter(RateLimitService rateLimits, ObjectMapper json) {
        this.rateLimits = rateLimits;
        this.json = json;
    }

    /**
     * Only the API is metered.
     *
     * <p>The SSE stream <strong>is</strong> filtered — it is a single request that opens a
     * connection, and it is charged exactly once, here, at connect. What it is exempt from is
     * per-<em>frame</em> accounting, which it gets for free: the frames are pushed by the server and
     * never come back through this filter. Charging per frame would throttle precisely the buyers
     * who are waiting patiently.
     *
     * <p>It used to be skipped entirely, which is not the same thing (ADR-011, {@code bot.md} §4):
     * a session could open unlimited streams and spend nothing at all.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        Object sessionId = request.getAttribute(SessionId.REQUEST_ATTRIBUTE);
        boolean allowed = sessionId == null || rateLimits.allowSession(sessionId.toString());
        if (allowed) {
            allowed = rateLimits.allowIp(clientIpOf(request));
        }

        if (!allowed) {
            writeRateLimited(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * The client's address, from the socket unless a <em>trusted</em> proxy says otherwise.
     *
     * <p>Behind a load balancer the real address arrives in {@code X-Forwarded-For} and the first
     * entry is the client; without honouring it every request would appear to come from the balancer
     * and the IP bucket would throttle the entire sale at once.
     *
     * <p><strong>But the header is client-supplied</strong> (ADR-039). Trusting it unconditionally —
     * which this filter did — let anyone rotate a fake address and mint an unlimited number of fresh
     * IP buckets, or poison someone else's. Since a caller who simply discards their cookie also
     * gets a fresh session bucket, that left no effective rate limit at all, while ADR-011 was
     * relying on the IP bucket as its backstop.
     *
     * <p>The list is <strong>empty by default</strong>, so an app with nothing in front of it uses
     * the socket address and the header is ignored. Populate it wherever a proxy terminates.
     */
    private String clientIpOf(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        if (!rateLimits.isTrustedProxy(peer)) {
            return peer;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return peer;
    }

    /** Written directly: a filter runs before the exception handlers can see it. */
    private void writeRateLimited(HttpServletResponse response) throws IOException {
        response.setStatus(ErrorCode.RATE_LIMITED.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setHeader("Retry-After", Integer.toString(RETRY_AFTER_SECONDS));
        json.writeValue(
                response.getOutputStream(),
                ProblemDetails.of(
                        ErrorCode.RATE_LIMITED,
                        "Too many requests. Please slow down and try again shortly.",
                        Map.of("retryable", true, "retryAfterSeconds", RETRY_AFTER_SECONDS)));
    }
}
