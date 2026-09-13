package com.flashseats.payment.gateway;

import java.util.UUID;
import lombok.extern.slf4j.Slf4j;

/**
 * An in-process gateway that behaves like a real one, steered by the payment method id.
 *
 * <p>The token names mirror the provider's test cards, so the demo UI, the integration tests and the
 * load harness can drive every branch of the checkout sequence — including the ones that are easy to
 * get wrong — with no keys and no network:
 *
 * <table border="1">
 *   <caption>Stub behaviour</caption>
 *   <tr><th>{@code paymentMethodId}</th><th>Outcome</th></tr>
 *   <tr><td>{@code pm_card_declined}</td><td>declined — the buyer keeps their seats and may retry</td></tr>
 *   <tr><td>{@code pm_card_error}</td><td>transport failure — a {@code 503}, seats retained, no attempt consumed</td></tr>
 *   <tr><td>{@code pm_card_authentication_required}</td><td>3-D Secure — {@code 402 PAYMENT_ACTION_REQUIRED}, then {@link #retrieve} settles it</td></tr>
 *   <tr><td>anything else</td><td>succeeded</td></tr>
 * </table>
 *
 * <p>{@link #retrieve} always answers {@code SUCCEEDED}: it models the buyer having completed the
 * challenge, which is the case the resume path exists to serve. That one token plus this one method
 * are what make the entire 3-D Secure round trip testable without Stripe.
 *
 * <p>Selected by {@link PaymentGatewayConfig} whenever {@code flashseats.payment.stripe.enabled} is
 * false, which is the default — so a clean checkout runs the full journey with no configuration.
 */
@Slf4j
public class StubPaymentGateway implements PaymentGateway {

    public static final String DECLINE_TOKEN = "pm_card_declined";
    public static final String ERROR_TOKEN = "pm_card_error";

    /**
     * The provider's own name for the test card that always demands a challenge.
     *
     * <p>Spelled exactly as the provider spells it, and the decline token below accepts the
     * provider's spelling alongside this repository's older one, so a script, a demo page or a test
     * drives <strong>the same token against either gateway</strong>. A stub whose vocabulary
     * diverges from the real one is a stub that passes and then fails in production over a string.
     */
    public static final String ACTION_TOKEN = "pm_card_authenticationRequired";

    private static final String ACTION_TOKEN_ALIAS = "pm_card_authentication_required";
    private static final String DECLINE_TOKEN_ALIAS = "pm_card_chargeDeclined";

    @Override
    public GatewayResult charge(GatewayCharge charge) {
        log.info(
                "Stub gateway charging {} {} for order {}",
                charge.amountCents(),
                charge.currency(),
                charge.orderNumber());

        return switch (charge.paymentMethodId()) {
            case DECLINE_TOKEN, DECLINE_TOKEN_ALIAS -> GatewayResult.declined(
                    "card_declined", "Your card was declined. Try a different card.");
            // Thrown, not returned: this is the transport failure the breaker counts, and returning
            // it would make the stub the one implementation that bypasses the breaker entirely.
            case ERROR_TOKEN -> throw new GatewayTransportException(
                    "gateway_error", "The payment provider is unavailable.");
            case ACTION_TOKEN, ACTION_TOKEN_ALIAS -> GatewayResult.requiresAction(
                    reference(), "stub_secret_" + UUID.randomUUID());
            default -> GatewayResult.succeeded(reference());
        };
    }

    @Override
    public GatewayResult retrieve(String gatewayReference) {
        log.info("Stub gateway retrieving {} — treating the challenge as completed", gatewayReference);
        return GatewayResult.succeeded(gatewayReference);
    }

    @Override
    public GatewayResult refund(String gatewayReference, long amountCents, String reason) {
        log.info("Stub gateway refunding {} against {} ({})", amountCents, gatewayReference, reason);
        return GatewayResult.succeeded(gatewayReference);
    }

    private static String reference() {
        return "stub_pi_" + UUID.randomUUID();
    }
}
