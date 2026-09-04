package com.flashseats.order.service;

import com.flashseats.order.config.OrderProperties;
import com.flashseats.shared.security.SignedToken;
import org.springframework.stereotype.Component;

/**
 * Signs and verifies the receipt token — a capability that grants read access to exactly one order.
 *
 * <p>It exists so the link in a confirmation email works: the buyer may open it on a different
 * device, in a different browser, weeks later, with no session cookie. Without it, the alternative
 * is a public lookup by order number, which is how an earlier design leaked buyers' email addresses
 * against a guessable reference (ADR-010).
 */
@Component
public class ReceiptTokens {

    private final OrderProperties properties;

    public ReceiptTokens(OrderProperties properties) {
        this.properties = properties;
    }

    public String issue(String orderNumber) {
        return SignedToken.sign(orderNumber, properties.getReceiptSecret());
    }

    /** True when {@code token} is a valid receipt token for exactly this order. */
    public boolean authorises(String token, String orderNumber) {
        return SignedToken.verify(token, properties.getReceiptSecret())
                .filter(orderNumber::equals)
                .isPresent();
    }
}
