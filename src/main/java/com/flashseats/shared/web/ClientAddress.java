package com.flashseats.shared.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The caller's address, resolved once per request by {@code bot}'s rate-limit filter against the
 * trusted-proxy set (ADR-039) and read from here by everything downstream. Never
 * {@code getRemoteAddr()}: behind nginx it is always the proxy.
 */
public final class ClientAddress {

    public static final String REQUEST_ATTRIBUTE = "flashseats.clientAddress";

    private ClientAddress() {}

    /**
     * @return the resolved address, or {@code null} if nothing resolved one — which happens only
     *     outside the filter chain, and is recorded honestly rather than guessed at
     */
    public static String of(HttpServletRequest request) {
        Object resolved = request.getAttribute(REQUEST_ATTRIBUTE);
        return resolved == null ? null : resolved.toString();
    }
}
