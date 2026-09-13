package com.flashseats.payment.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flashseats.payment.config.PaymentProperties;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which provider states mean what.
 *
 * <p>This mapping is the whole of the gateway's judgement, and every wrong answer in it is
 * expensive in a different way: a false success confirms an order for money that has not moved, a
 * false decline tells a buyer their good card was refused, and a false challenge hands the client a
 * secret it cannot use.
 *
 * <p>A unit test against constructed intents rather than a live account, because the point is the
 * mapping and not the network — and because a real account cannot be made to produce
 * {@code requires_confirmation} on demand, which is exactly the case that used to be wrong.
 */
@DisplayName("Provider statuses map onto the three answers this system understands")
class StripeStatusMappingTest {

    private final StripePaymentGateway gateway = new StripePaymentGateway(new PaymentProperties.Stripe());

    @Test
    @DisplayName("succeeded is a success, and carries the intent id")
    void succeeded() {
        GatewayResult result = gateway.classify(intent("succeeded"));

        assertThat(result.outcome()).isEqualTo(GatewayResult.Outcome.SUCCEEDED);
        assertThat(result.gatewayReference()).isEqualTo("pi_test");
    }

    @Test
    @DisplayName("requires_action is a challenge, and carries BOTH the secret and the intent id")
    void requiresAction() {
        PaymentIntent intent = intent("requires_action");
        intent.setClientSecret("pi_test_secret_abc");

        GatewayResult result = gateway.classify(intent);

        assertThat(result.outcome()).isEqualTo(GatewayResult.Outcome.REQUIRES_ACTION);
        assertThat(result.clientSecret()).isEqualTo("pi_test_secret_abc");
        // The id is what the resume retrieves. Without it the buyer authenticates one charge and
        // the retry opens another.
        assertThat(result.gatewayReference()).isEqualTo("pi_test");
    }

    @Test
    @DisplayName("requires_confirmation is NOT a challenge — it would loop the buyer for ever")
    void requiresConfirmationIsNotAChallenge() {
        // The server confirms this state, not the buyer. Returning PAYMENT_ACTION_REQUIRED would
        // hand the client a clientSecret whose handleNextAction does nothing, so every re-POST
        // would retrieve the same state and answer 402 again until the hold expired.
        assertThatThrownBy(() -> gateway.classify(intent("requires_confirmation")))
                .isInstanceOf(GatewayTransportException.class);
    }

    @Test
    @DisplayName("requires_payment_method is a decline, with the provider's own copy")
    void declined() {
        PaymentIntent intent = intent("requires_payment_method");
        StripeError error = new StripeError();
        error.setDeclineCode("insufficient_funds");
        error.setMessage("Your card has insufficient funds.");
        intent.setLastPaymentError(error);

        GatewayResult result = gateway.classify(intent);

        assertThat(result.outcome()).isEqualTo(GatewayResult.Outcome.DECLINED);
        assertThat(result.failureCode()).isEqualTo("insufficient_funds");
        // Shown to the buyer, so it has to be the provider's customer-facing copy.
        assertThat(result.failureReason()).isEqualTo("Your card has insufficient funds.");
    }

    @Test
    @DisplayName("processing is not a success — the seats would go out for money that has not moved")
    void processingIsNotSuccess() {
        assertThatThrownBy(() -> gateway.classify(intent("processing")))
                .isInstanceOf(GatewayTransportException.class);
    }

    @Test
    @DisplayName("An unknown status is a transport fault, never a guess")
    void unknownStatus() {
        assertThatThrownBy(() -> gateway.classify(intent("some_future_state")))
                .isInstanceOf(GatewayTransportException.class)
                .hasMessageContaining("some_future_state");
    }

    private static PaymentIntent intent(String status) {
        PaymentIntent intent = new PaymentIntent();
        intent.setId("pi_test");
        intent.setStatus(status);
        return intent;
    }
}
