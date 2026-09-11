package com.flashseats.shared.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Assigns every request a W3C trace id, so the {@code traceId} on an error response and the trace id
 * in the logs are the same string.
 *
 * <p>Global standards §1 puts {@code traceId} on <em>every</em> problem response — it is what a
 * support conversation starts from, and it is worthless if it cannot be found in the logs. An
 * inbound {@code traceparent} header is honoured when present so a trace crossing the proxy stays
 * whole.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    private static final String TRACEPARENT = "traceparent";

    /** The current request's trace id, or a placeholder outside a request (a scheduled job). */
    public static String current() {
        String id = MDC.get(MDC_KEY);
        return id != null ? id : "no-trace";
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        MDC.put(MDC_KEY, traceIdFor(request));
        try {
            chain.doFilter(request, response);
        } finally {
            // Virtual threads are pooled by the runtime; a stale MDC entry would mislabel the next
            // request that lands on this carrier.
            MDC.remove(MDC_KEY);
        }
    }

    /** {@code traceparent} is {@code version-traceid-spanid-flags}; the trace id is field 1. */
    private String traceIdFor(HttpServletRequest request) {
        String header = request.getHeader(TRACEPARENT);
        if (header != null) {
            String[] parts = header.split("-");
            if (parts.length >= 2 && parts[1].length() == 32) {
                return parts[1];
            }
        }
        return newTraceId();
    }

    private String newTraceId() {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        return "%016x%016x".formatted(random.nextLong(), random.nextLong());
    }
}
