package com.flashseats.order.service;

import com.flashseats.order.config.OrderProperties;
import com.flashseats.shared.security.SignedToken;
import com.flashseats.shared.time.Expiry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Signs and verifies the receipt token: a capability to read exactly one order, so the email link
 * works on another device with no session (ADR-010).
 *
 * <p>Payload is {@code orderNumber:expiryEpochSecond:nonce}, like {@code QueueTokens} (ADR-039). The
 * expiry exists because a link in mail and history leaks and must stop working. The nonce exists
 * because without it the token is derivable from sequential order numbers. Domain-separated by
 * kind, so it never verifies as another token type.
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
                .filter(parts -> Expiry.notPassed(clock, parts[1]))
                .isPresent();
    }
}
