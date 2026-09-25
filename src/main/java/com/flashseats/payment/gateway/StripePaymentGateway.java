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
 * Stripe, behind the interface the stub already satisfied. Server-confirmed PaymentIntents keep the
 * {@code FE_SPEC} §2 checkout body and ADR-001's ordering unchanged.
 *
 * <p>Load-bearing details: {@code holdToken} in metadata (ADR-014); {@code maxNetworkRetries(0)},
 * because re-POSTing the checkout body is already the retry; {@code allowRedirects(NEVER)}, because
 * no off-site return path is modelled. Transport failures are thrown for the breaker, declines are
 * returned (ADR-052).
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
    /**
     * A server-confirmed, card-only intent in one round trip ({@code setConfirm(true)}), which keeps
     * ADR-001's charge-then-consume ordering.
     *
     * <p>Card only is a requirement: a redirect-based method needs a return URL, and there is no resume
     * endpoint (ADR-054), so it would strand a buyer holding seats. Naming the type also turns off
     * automatic payment methods. Card 3-D Secure still happens, as {@code requires_action}. Order number
     * and hold token go into metadata for reconciliation and the webhook.
     */
    public GatewayResult charge(GatewayCharge charge) {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(charge.amountCents())
                .setCurrency(charge.currency().toLowerCase())
                .setPaymentMethod(charge.paymentMethodId())
                .setConfirm(true)
                .addPaymentMethodType("card")
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
    public GatewayResult refund(
            String gatewayReference,
            long amountCents,
            String reason) {

        RefundCreateParams params =
                RefundCreateParams.builder()
                        .setPaymentIntent(gatewayReference)
                        .setAmount(amountCents)

                        // Stripe only accepts a small closed vocabulary
                        // for this field. The application-specific reason
                        // is preserved in metadata below.
                        .setReason(
                                RefundCreateParams.Reason.REQUESTED_BY_CUSTOMER)

                        .putMetadata("flashseatsReason", reason)
                        .build();

        /*
         * Full refunds only, so intent id + amount identify the operation. Retrying after a crash returns
         * Stripe's original result instead of refunding twice.
         */
        RequestOptions refundOptions =
                baseOptions
                        .toBuilderFullCopy()
                        .setIdempotencyKey(
                                "flashseats-refund-"
                                        + gatewayReference
                                        + "-"
                                        + amountCents)
                        .build();

        try {
            stripe.refunds().create(params, refundOptions);

            return GatewayResult.succeeded(gatewayReference);

        } catch (StripeException failed) {
            log.error(
                    "Stripe refused a refund against {}",
                    gatewayReference,
                    failed);

            return GatewayResult.error(
                    codeOf(failed),
                    messageOf(failed));
        }
    }
    /**
     * Maps an intent's status onto the three answers this system understands. Only
     * {@code requires_action} is a challenge. {@code processing} and {@code requires_capture} are not
     * successes: money has not moved. Everything unmodelled becomes a retryable {@code 503}, with seats
     * kept and no attempt consumed.
     */
    GatewayResult classify(PaymentIntent intent) {
        return switch (intent.getStatus()) {
            case "succeeded" -> GatewayResult.succeeded(intent.getId());
            case "requires_action" -> GatewayResult.requiresAction(
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
