package com.flashseats.shared.identity;

import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

    @PostMapping("/reset")
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
