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
    /**
     * A server-confirmed, card-only intent.
     *
     * <p><strong>Card only, and that is a requirement rather than a preference.</strong> A
     * redirect-based payment method sends the buyer to another origin and expects them to come back to
     * a URL we own — and there is no resume endpoint to come back to, deliberately: the retry is
     * re-POSTing the same checkout body, and the server retrieves the pending intent (ADR-054). A
     * method that needed a return URL would strand a buyer holding live seats. Naming the type
     * explicitly also turns off automatic payment methods, which is where such a method would
     * otherwise arrive from without anyone choosing it.
     *
     * <p>Card 3-D Secure still happens and is wanted: it surfaces as {@code requires_action}, which
     * {@code PaymentStatus.PROCESSING} parks and the next identical POST settles.
     *
     * <p>{@code setConfirm(true)} is what makes this one round trip rather than create-then-confirm,
     * so ADR-001's ordering — charge, then consume the hold inside the commit — holds with one
     * provider call in the middle of the checkout transaction boundary.
     *
     * <p>The order number and hold token go into metadata because they are what reconciliation reads
     * when the two ledgers disagree; the webhook path finds its order by {@code holdToken}.
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
        * A refund is a money movement, so the provider request itself must be
        * idempotent.
        *
        * FlashSeats performs full refunds, so the PaymentIntent id + amount
        * uniquely identify the refund operation.
        *
        * If our application crashes after Stripe succeeds but before our DB
        * records REFUNDED, retrying this exact request returns Stripe's
        * idempotent result instead of creating another refund.
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
     * Maps an intent's status onto the three answers this system understands.
     *
     * <p>Only {@code requires_action} is a challenge. {@code requires_confirmation} means the
     * <em>server</em> has yet to confirm, which with {@code setConfirm(true)} should not occur —
     * and answering it with {@code PAYMENT_ACTION_REQUIRED} would be worse than useless: the client
     * would receive a {@code clientSecret} whose {@code handleNextAction} does nothing, re-POST,
     * retrieve the same state, and be told to authenticate again for the life of the hold.
     *
     * <p>{@code processing} and {@code requires_capture} are deliberately <em>not</em> successes
     * either: confirming an order against either would hand over seats for money that has not moved.
     * All of them fall to the transport branch, which is the honest "this is a state we do not
     * model" — a retryable {@code 503} with the seats retained and no attempt consumed.
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
