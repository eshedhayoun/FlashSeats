package com.flashseats.queue.service;

import com.flashseats.queue.config.QueueProperties;
import com.flashseats.shared.security.SignedToken;
import com.flashseats.shared.time.Expiry;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Mints and verifies the waiting room's two capability tokens. Payload
 * {@code kind:eventId:sessionId:expiryEpochSecond:nonce}: the kind stops a pass posing as an
 * admission, the event and session bind it, the expiry fails stale tokens without Redis, and the nonce
 * separates same-second tokens. The signature proves authenticity; the Redis key proves the token is
 * unspent. Both checks are needed.
 */
@Component
public class QueueTokens {

    private static final String PASS = "pass";
    private static final String ADMISSION = "admit";
    private static final String SEPARATOR = ":";

    private final QueueProperties properties;
    private final Clock clock;

    public QueueTokens(QueueProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String mintPass(long eventId, String sessionId) {
        return mint(PASS, eventId, sessionId, properties.getPassTtlSeconds());
    }

    public String mintAdmission(long eventId, String sessionId) {
        return mint(ADMISSION, eventId, sessionId, properties.getAdmissionTtlSeconds());
    }

    public boolean isValidPass(String token, long eventId, String sessionId) {
        return isValid(token, PASS, eventId, sessionId);
    }

    public boolean isValidAdmission(String token, long eventId, String sessionId) {
        return isValid(token, ADMISSION, eventId, sessionId);
    }

    private String mint(String kind, long eventId, String sessionId, int ttlSeconds) {
        String payload = String.join(
                SEPARATOR,
                kind,
                Long.toString(eventId),
                sessionId,
                Long.toString(clock.instant().plusSeconds(ttlSeconds).getEpochSecond()),
                UUID.randomUUID().toString());
        return SignedToken.sign(kind, payload, properties.getPassSecret());
    }

    private boolean isValid(String token, String kind, long eventId, String sessionId) {
        return SignedToken.verify(kind, token, properties.getPassSecret())
                .map(payload -> payload.split(SEPARATOR))
                .filter(parts -> parts.length == 5)
                .filter(parts -> kind.equals(parts[0]))
                .filter(parts -> Long.toString(eventId).equals(parts[1]))
                .filter(parts -> sessionId.equals(parts[2]))
                .filter(parts -> Expiry.notPassed(clock, parts[3]))
                .isPresent();
    }
}
