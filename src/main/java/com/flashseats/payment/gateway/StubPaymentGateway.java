package com.flashseats.payment.gateway;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An in-process gateway that behaves like a real one, steered by the payment method id.
 *
 * <p>The token names mirror the provider's test cards, so the demo UI and the integration tests can
 * drive every branch of the checkout sequence — including the ones that are easy to get wrong:
 *
 * <table border="1">
 *   <caption>Stub behaviour</caption>
 *   <tr><th>{@code paymentMethodId}</th><th>Outcome</th></tr>
 *   <tr><td>{@code pm_card_declined}</td><td>declined — the buyer keeps their seats and may retry</td></tr>
 *   <tr><td>{@code pm_card_error}</td><td>provider error — a {@code 503}, seats retained</td></tr>
 *   <tr><td>anything else</td><td>succeeded</td></tr>
 * </table>
 *
 * <p>Registered by {@link PaymentGatewayConfig} only when no other {@link PaymentGateway} bean
 * exists, so adding a real one replaces this without touching any call site.
 */
public class StubPaymentGateway implements PaymentGateway {

    public static final String DECLINE_TOKEN = "pm_card_declined";
    public static final String ERROR_TOKEN = "pm_card_error";

    private static final Logger log = LoggerFactory.getLogger(StubPaymentGateway.class);

    @Override
    public GatewayResult charge(GatewayCharge charge) {
        log.info(
                "Stub gateway charging {} {} for order {}",
                charge.amountCents(),
                charge.currency(),
                charge.orderNumber());

        return switch (charge.paymentMethodId()) {
            case DECLINE_TOKEN -> GatewayResult.declined(
                    "card_declined", "Your card was declined. Try a different card.");
            case ERROR_TOKEN -> GatewayResult.error(
                    "gateway_error", "The payment provider is unavailable.");
            default -> GatewayResult.succeeded("stub_pi_" + UUID.randomUUID());
        };
    }

    @Override
    public GatewayResult refund(String gatewayReference, long amountCents, String reason) {
        log.info("Stub gateway refunding {} against {} ({})", amountCents, gatewayReference, reason);
        return GatewayResult.succeeded(gatewayReference);
    }
}
