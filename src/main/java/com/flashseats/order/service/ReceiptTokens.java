package com.flashseats.order.service;

import com.flashseats.order.config.OrderProperties;
import com.flashseats.shared.security.SignedToken;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Signs and verifies the receipt token — a capability that grants read access to exactly one order.
 *
 * <p>It exists so the link in a confirmation email works: the buyer may open it on a different
 * device, in a different browser, weeks later, with no session cookie. Without it, the alternative
 * is a public lookup by order number, which is how an earlier design leaked buyers' email addresses
 * against a guessable reference (ADR-010).
 *
 * <p>Payload is {@code orderNumber:expiryEpochSecond:nonce}, mirroring
 * {@link com.flashseats.queue.service.QueueTokens} — and every field is there for a reason
 * (ADR-039):
 *
 * <ul>
 *   <li><strong>expiry</strong> — a receipt link travels through forwarded mail, browser history and
 *       {@code Referer} headers. An unlimited-lifetime capability in a URL is one that leaks and
 *       then keeps working. The buyer's cookie is the path that never expires; this is the one that
 *       has to.
 *   <li><strong>nonce</strong> — without it the token is a pure function of the order number and the
 *       secret. Combined with sequential order numbers, anyone who learned the secret could derive
 *       every buyer's link by counting, rather than having to observe one.
 * </ul>
 *
 * <p>The token is also domain-separated by kind, so it can never verify as the session cookie or a
 * queue pass even where a deployment reuses one secret.
 */
@Component
public class ReceiptTokens {

    /** Domain-separates this token from every other signed token (ADR-039). */
    private static final String KIND = "receipt";

    private static final String SEPARATOR = ":";

    private final OrderProperties properties;
    private final Clock clock;

    public ReceiptTokens(OrderProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    public String issue(String orderNumber) {
        Instant expiresAt =
                clock.instant().plus(Duration.ofDays(properties.getReceiptTokenTtlDays()));
        String payload = String.join(
                SEPARATOR,
                orderNumber,
                Long.toString(expiresAt.getEpochSecond()),
                UUID.randomUUID().toString());
        return SignedToken.sign(KIND, payload, properties.getReceiptSecret());
    }

    /** True when {@code token} is a valid, unexpired receipt token for exactly this order. */
    public boolean authorises(String token, String orderNumber) {
        return SignedToken.verify(KIND, token, properties.getReceiptSecret())
                .map(payload -> payload.split(SEPARATOR))
                .filter(parts -> parts.length == 3)
                .filter(parts -> orderNumber.equals(parts[0]))
                .filter(parts -> notExpired(parts[1]))
                .isPresent();
    }

    private boolean notExpired(String expiryEpochSecond) {
        try {
            return clock.instant().isBefore(Instant.ofEpochSecond(Long.parseLong(expiryEpochSecond)));
        } catch (NumberFormatException tampered) {
            return false;
        }
    }
}
