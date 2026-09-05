package com.flashseats.queue.service;

import com.flashseats.queue.config.QueueProperties;
import com.flashseats.shared.security.SignedToken;
import com.flashseats.shared.time.Expiry;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Mints and verifies the two capability tokens the waiting room issues.
 *
 * <p>Payload is {@code kind:eventId:sessionId:expiryEpochSecond:nonce}. Every field earns its place:
 *
 * <ul>
 *   <li><strong>kind</strong> — so a 120 s pass can never be presented as a 600 s admission session
 *   <li><strong>eventId</strong> — so a pass for one sale cannot open another
 *   <li><strong>sessionId</strong> — so a token is useless to anyone who intercepts it
 *   <li><strong>expiry</strong> — so a forged-but-stale token fails without a Redis round trip
 *   <li><strong>nonce</strong> — so two passes minted in the same second are distinguishable
 * </ul>
 *
 * <p>The signature proves authenticity; the matching Redis key proves the token has not been spent
 * or revoked. Both checks are needed — neither is sufficient alone.
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
