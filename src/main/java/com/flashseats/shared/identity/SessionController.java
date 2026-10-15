package com.flashseats.shared.identity;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Browser-session utilities for the demo client. */
@RestController
@RequestMapping("/api/v1/session")
public class SessionController {

    private final SessionProperties properties;

    public SessionController(SessionProperties properties) {
        this.properties = properties;
    }

    /**
     * Expires the {@code fsid} cookie so the demo page can start over as a new visitor.
     *
     * <p><strong>JSON only, and that is the security control, not a formality</strong> (ADR-060).
     * The session is the buyer's queue position and their only authority over their hold, and CSRF
     * is disabled (§10 S6) — so a hidden form on any site could otherwise throw a waiting buyer out
     * of the line. A form can send only {@code urlencoded}, {@code multipart} or {@code text/plain};
     * a cross-origin {@code fetch} with {@code application/json} needs a CORS preflight that nothing
     * here grants. Anything else answers {@code 415} before this method runs.
     */
    @PostMapping(value = "/reset", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> reset() {
        SessionProperties.Cookie config = properties.getCookie();
        ResponseCookie expired = ResponseCookie.from(config.getName(), "")
                .httpOnly(true)
                .secure(config.isSecure())
                .sameSite(config.getSameSite())
                .path("/")
                .maxAge(Duration.ZERO)
                .build();

        return ResponseEntity.status(HttpStatus.NO_CONTENT)
                .header(HttpHeaders.SET_COOKIE, expired.toString())
                .build();
    }
}
