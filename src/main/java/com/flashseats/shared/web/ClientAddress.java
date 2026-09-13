package com.flashseats.shared.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The caller's address, as resolved once per request.
 *
 * <p>Resolving {@code X-Forwarded-For} against the trusted-proxy set is a security decision
 * (ADR-039) and must happen in exactly <strong>one</strong> place — {@code bot}'s rate-limit filter,
 * which is the component the decision protects. Everything downstream reads the answer from here.
 *
 * <p>It lives in {@code shared} for the same reason {@link com.flashseats.shared.identity.SessionId}
 * does: it crosses module boundaries as a request attribute rather than as a type, so no module has
 * to import another module's filter package to learn who is calling.
 *
 * <p>Calling {@code getRemoteAddr()} instead is the mistake this class exists to prevent. Behind
 * nginx it is always the proxy, so every row that recorded it would say the same meaningless thing
 * in the only deployment shape that matters.
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
