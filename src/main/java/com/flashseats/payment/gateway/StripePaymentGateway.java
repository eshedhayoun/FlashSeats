package com.flashseats.payment.gateway;

import com.flashseats.payment.config.PaymentProperties;
import com.stripe.StripeClient;
import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.ApiException;
import com.stripe.exception.CardException;
import com.stripe.exception.RateLimitException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeError;
import com.stripe.net.RequestOptions;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.RefundCreateParams;
import lombok.extern.slf4j.Slf4j;

/**
 * Stripe, behind the interface the stub already satisfied.
 *
 * <p>Server-confirmed PaymentIntents: the client sends a {@code pm_...} and this charges it, which
 * is what keeps {@code FE_SPEC} §2's checkout body, ADR-001's charge-then-consume ordering and the
 * whole synchronous {@code 402}/{@code 409} contract unchanged. The alternative — Payment Element,
 * where the browser confirms and the webhook is the primary settlement path — would invert that
 * sequence and make most of the contract dead.
 *
 * <p>Three details are load-bearing:
 *
 * <ul>
 *   <li><strong>{@code holdToken} goes in metadata.</strong> It is how the webhook finds the order
 *       when the HTTP response was lost, and it is the natural key the rest of the system anchors on
 *       (ADR-014).
 *   <li><strong>{@code maxNetworkRetries(0)}.</strong> Stripe's client-side retry is a second retry
 *       mechanism, and this system already has one: re-POSTing the same checkout body.
 *   <li><strong>{@code allowRedirects(NEVER)}.</strong> A redirect-based method would take the buyer
 *       off-site mid-hold with no return path that this API models.
 * </ul>
 *
 * <p>Transport failures are <em>thrown</em> as {@link GatewayTransportException} so the circuit
 * breaker can count them; declines are <em>returned</em>, because a refused card is a correct answer
 * and a breaker that counted declines would open during an ordinary burst of expired cards.
 */
@Slf4j
public class StripePaymentGateway implements PaymentGateway {

    private final StripeClient stripe;
    private final RequestOptions baseOptions;

    public StripePaymentGateway(PaymentProperties.Stripe properties) {
        this.stripe = new StripeClient(properties.getApiKey());
        this.baseOptions = RequestOptions.builder()
                .setConnectTimeout(properties.getConnectTimeoutMillis())
                .setReadTimeout(properties.getReadTimeoutMillis())
                .setMaxNetworkRetries(0)
                .build();
    }

    @Override
    public GatewayResult charge(GatewayCharge charge) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(charge.amountCents())
                .setCurrency(charge.currency().toLowerCase())
                .setPaymentMethod(charge.paymentMethodId())
                .setConfirm(true)
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .setAllowRedirects(
                                PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                        .build())
                .putMetadata("orderNumber", charge.orderNumber())
                .putMetadata("holdToken", charge.holdToken())
                .build();

        RequestOptions options = charge.clientIdempotencyKey() == null
                        || charge.clientIdempotencyKey().isBlank()
                ? baseOptions
                : baseOptions.toBuilderFullCopy()
                        .setIdempotencyKey(charge.clientIdempotencyKey())
                        .build();

        try {
            return classify(stripe.paymentIntents().create(params, options));
        } catch (CardException declined) {
            return GatewayResult.declined(declineCodeOf(declined), messageOf(declined));
        } catch (StripeException failed) {
            throw transport("charge", charge.orderNumber(), failed);
        }
    }

    @Override
    public GatewayResult retrieve(String gatewayReference) {
        try {
            return classify(stripe.paymentIntents().retrieve(gatewayReference, baseOptions));
        } catch (CardException declined) {
            return GatewayResult.declined(declineCodeOf(declined), messageOf(declined));
        } catch (StripeException failed) {
            throw transport("retrieve", gatewayReference, failed);
        }
    }

    @Override
    public GatewayResult refund(String gatewayReference, long amountCents, String reason) {
        RefundCreateParams params = RefundCreateParams.builder()
                .setPaymentIntent(gatewayReference)
                .setAmount(amountCents)
                // Stripe's reason vocabulary is a closed set of three. The real reason — which is
                // always "we took the money and could not deliver the seats" — goes in metadata,
                // where it survives for reconciliation instead of being rounded to the nearest enum.
                .setReason(RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER)
                .putMetadata("flashseatsReason", reason)
                .build();
        try {
            stripe.refunds().create(params, baseOptions);
            return GatewayResult.succeeded(gatewayReference);
        } catch (StripeException failed) {
            // A failed refund is money we hold and should not, and it is NOT a transport concern:
            // it must not be allowed to trip the breaker and it must not be retried blindly. The
            // caller logs it for manual reconciliation.
            log.error("Stripe refused a refund against {}", gatewayReference, failed);
            return GatewayResult.error(codeOf(failed), messageOf(failed));
        }
    }

    /**
     * Maps an intent's status onto the three answers this system understands.
     *
     * <p>{@code processing} and {@code requires_capture} are deliberately <em>not</em> successes:
     * confirming an order against either would hand over seats for money that has not moved. They
     * fall to the transport branch, which retains the hold and lets the buyer retry.
     */
    private GatewayResult classify(PaymentIntent intent) {
        return switch (intent.getStatus()) {
            case "succeeded" -> GatewayResult.succeeded(intent.getId());
            case "requires_action", "requires_confirmation" -> GatewayResult.requiresAction(
                    intent.getId(), intent.getClientSecret());
            case "requires_payment_method", "canceled" -> GatewayResult.declined(
                    errorCodeOf(intent), errorMessageOf(intent));
            default -> throw new GatewayTransportException(
                    "unexpected_intent_status",
                    "PaymentIntent " + intent.getId() + " is in state " + intent.getStatus());
        };
    }

    private GatewayTransportException transport(String operation, String subject, StripeException failed) {
        String code = switch (failed) {
            case ApiConnectionException ignored -> "api_connection_error";
            case RateLimitException ignored -> "rate_limit_error";
            case ApiException ignored -> "api_error";
            default -> codeOf(failed);
        };
        log.warn("Stripe {} failed for {} ({})", operation, subject, code, failed);
        return new GatewayTransportException(code, messageOf(failed), failed);
    }

    private static String declineCodeOf(CardException declined) {
        return declined.getDeclineCode() != null ? declined.getDeclineCode() : codeOf(declined);
    }

    private static String codeOf(StripeException failed) {
        return failed.getCode() != null ? failed.getCode() : "stripe_error";
    }

    /**
     * Stripe's own message, never a stack trace.
     *
     * <p>It reaches the buyer as {@code detail} in the problem document, so it has to be the
     * provider's customer-facing copy ("Your card was declined") rather than anything internal.
     */
    private static String messageOf(StripeException failed) {
        StripeError error = failed.getStripeError();
        if (error != null && error.getMessage() != null) {
            return error.getMessage();
        }
        return "The payment could not be completed.";
    }

    private static String errorCodeOf(PaymentIntent intent) {
        StripeError error = intent.getLastPaymentError();
        if (error == null) {
            return "card_declined";
        }
        return error.getDeclineCode() != null ? error.getDeclineCode() : error.getCode();
    }

    private static String errorMessageOf(PaymentIntent intent) {
        StripeError error = intent.getLastPaymentError();
        return error != null && error.getMessage() != null
                ? error.getMessage()
                : "Your card was declined. Try a different card.";
    }
}
