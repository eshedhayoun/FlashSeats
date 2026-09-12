package com.flashseats.shared.identity;

import com.flashseats.shared.security.SignedToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Issues and verifies the signed {@code fsid} cookie that is the system's only source of identity.
 *
 * <p>Format: {@code base64url(uuid).base64url(HMAC-SHA256(uuid, secret))}, set {@code HttpOnly} so
 * JavaScript can never read it. Queue position, hold ownership and order lookup all key off this
 * value, which is why it may never arrive in a request body or header (ADR-010).
 *
 * <p>A tampered cookie is <strong>replaced</strong> with a fresh identity rather than rejected. The
 * visitor did nothing an error page would help with, and a hard failure on a corrupted cookie would
 * strand them with no way to recover.
 *
 * <p><strong>It lives in the kernel, beside {@link SessionIdArgumentResolver}.</strong> It used to be
 * in {@code bot}, which meant the one thing every module's authorisation rests on was owned by the
 * abuse-defence module, and the mint/verify half was a package away from the type/resolve half with
 * nothing but a request-attribute string joining them. {@code bot} is now purely rate limiting.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
public class SessionIdentityFilter extends OncePerRequestFilter {

    /** Domain-separates the cookie's signature from every other signed token (ADR-039). */
    private static final String KIND = "fsid";

    private final SessionProperties properties;

    public SessionIdentityFilter(SessionProperties properties) {
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String sessionId = verifiedCookie(request).orElseGet(() -> issueTo(response));
        request.setAttribute(SessionId.REQUEST_ATTRIBUTE, sessionId);
        chain.doFilter(request, response);
    }

    private Optional<String> verifiedCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return Optional.empty();
        }
        return Arrays.stream(cookies)
                .filter(c -> properties.getCookie().getName().equals(c.getName()))
                .map(Cookie::getValue)
                .flatMap(value ->
                        SignedToken.verify(KIND, value, properties.getSecret()).stream())
                .findFirst();
    }

    private String issueTo(HttpServletResponse response) {
        String sessionId = UUID.randomUUID().toString();
        SessionProperties.Cookie config = properties.getCookie();

        ResponseCookie cookie = ResponseCookie
                .from(
                        config.getName(),
                        SignedToken.sign(KIND, sessionId, properties.getSecret()))
                .httpOnly(true)
                .secure(config.isSecure())
                .sameSite(config.getSameSite())
                .path("/")
                .maxAge(Duration.ofSeconds(config.getMaxAgeSeconds()))
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
        return sessionId;
    }
}
