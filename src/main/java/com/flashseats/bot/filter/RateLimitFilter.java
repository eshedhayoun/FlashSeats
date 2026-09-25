package com.flashseats.bot.filter;

import com.flashseats.bot.model.BotOutcome;
import com.flashseats.bot.model.IpRuleAction;
import com.flashseats.bot.service.BotAuditService;
import com.flashseats.bot.service.BotMetrics;
import com.flashseats.bot.service.IpRuleService;
import com.flashseats.bot.service.RateLimitService;
import com.flashseats.shared.error.ErrorCode;
import com.flashseats.shared.error.ProblemDetails;
import com.flashseats.shared.identity.SessionId;
import com.flashseats.shared.identity.SessionIdentityFilter;
import com.flashseats.shared.web.ClientAddress;
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
 * Applies the operator's address rules, then the session and IP token buckets. Runs after
 * {@link SessionIdentityFilter}, so every request has an identity to charge. The rule lookup is a
 * memory read, never a query (ADR-055).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int RETRY_AFTER_SECONDS = 2;

    private final RateLimitService rateLimits;
    private final IpRuleService ipRules;
    private final BotAuditService audit;
    private final ObjectMapper json;
    private final BotMetrics metrics;

    public RateLimitFilter(
        RateLimitService rateLimits,
        IpRuleService ipRules,
        BotAuditService audit,
        BotMetrics metrics,
        ObjectMapper json) {
        this.rateLimits = rateLimits;
        this.ipRules = ipRules;
        this.audit = audit;
        this.json = json;
        this.metrics = metrics;
    }

    /**
     * Only the API is metered. The SSE stream is charged once, at connect; its server-pushed frames
     * never pass back through this filter, so patient buyers are not throttled per frame (ADR-011).
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
        String clientIp = clientIpOf(request);
        // Published for the rest of the request. This filter is the ONLY place X-Forwarded-For is
        // resolved against the trusted-proxy set (ADR-039), so anything downstream that wants the
        // caller's address reads it from here rather than resolving it a second way.
        request.setAttribute(ClientAddress.REQUEST_ATTRIBUTE, clientIp);
        IpRuleAction rule = ipRules.actionFor(clientIp);

        if (rule == IpRuleAction.DENY) {
            metrics.recordRefusal(BotOutcome.IP_BLOCKED);
            audit.record(
                    sessionId == null ? null : sessionId.toString(),
                    clientIp,
                    request.getRequestURI(),
                    BotOutcome.IP_BLOCKED,
                    null);
            writeProblem(response, ErrorCode.IP_BLOCKED,
                    "This request was refused. If you believe this is a mistake, contact support.",
                    Map.of("retryable", false));
            return;
        }

        // Short-circuited on purpose: a request already refused by its session bucket must not also
        // spend a token from the shared IP bucket, which thousands of buyers behind one NAT share.
        //
        // An ALLOW rule skips the IP bucket and NOTHING ELSE. It exists for a known shared egress —
        // an office, a partner's proxy — where hundreds of real buyers share one address and the
        // coarse flood backstop would throttle all of them. It is not a statement that the traffic
        // is trusted, so the per-session control still applies to every one of them.
        boolean sessionOk = sessionId == null || rateLimits.allowSession(sessionId.toString());
        boolean allowed = sessionOk && (rule == IpRuleAction.ALLOW || rateLimits.allowIp(clientIp));

        if (!allowed) {
            metrics.recordRefusal(BotOutcome.RATE_LIMITED);
            audit.record(
                    sessionId == null ? null : sessionId.toString(),
                    clientIp,
                    request.getRequestURI(),
                    BotOutcome.RATE_LIMITED,
                    sessionOk ? "ip bucket" : "session bucket");
            writeRateLimited(response);
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * The client's address: the socket, unless a <em>trusted</em> proxy says otherwise (ADR-039).
     * {@code X-Forwarded-For} is client-supplied, so it is honoured only from
     * {@code flashseats.bot.trusted-proxies}, which is empty by default. Trusting it blindly gave anyone
     * unlimited fresh IP buckets.
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
        response.setHeader("Retry-After", Integer.toString(RETRY_AFTER_SECONDS));
        writeProblem(
                response,
                ErrorCode.RATE_LIMITED,
                "Too many requests. Please slow down and try again shortly.",
                Map.of("retryable", true, "retryAfterSeconds", RETRY_AFTER_SECONDS));
    }

    private void writeProblem(
            HttpServletResponse response, ErrorCode code, String detail, Map<String, Object> extensions)
            throws IOException {
        response.setStatus(code.status().value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        json.writeValue(response.getOutputStream(), ProblemDetails.of(code, detail, extensions));
    }
}
