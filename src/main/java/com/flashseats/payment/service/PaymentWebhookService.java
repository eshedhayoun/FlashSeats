package com.flashseats.payment.service;

import com.flashseats.payment.config.PaymentProperties;
import com.flashseats.payment.event.PaymentSettledEvent;
import com.flashseats.payment.exception.PaymentErrors;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.model.Event;
import com.stripe.model.EventDataObjectDeserializer;
import com.stripe.model.PaymentIntent;
import com.stripe.model.StripeObject;
import com.stripe.net.Webhook;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
/**
 * Verifies a provider delivery, claims it once, and lets {@code order} settle it.
 *
 * <p>This is the path that exists because the synchronous one can be cut: the charge succeeded and
 * the buyer never saw the response — a dropped connection, a killed replica, a closed laptop. The
 * money moved and nothing in this system knows it.
 *
 * <p><strong>Not {@code @Transactional}.</strong> The claim and its release are short transactions
 * on {@link WebhookEventStore}; the settlement between them is {@code order}'s own transaction,
 * reached by an event. One transaction spanning all three would put another module's work — and, on
 * the refund arm, a network round trip to the provider — inside a SQL transaction (ADR-023).
 *
 * <p>The sequence, and why each step is where it is:
 *
 * <ol>
 *   <li><strong>Verify the signature over the raw bytes.</strong> The endpoint is unauthenticated by
 *       necessity — the provider cannot hold a session — so this is the only gate, and it has to
 *       happen before anything reads the body as anything but bytes.
 *   <li><strong>Ignore every other event type, with a {@code 200}.</strong> A non-2xx would ask the
 *       provider to redeliver something we will go on ignoring.
 *   <li><strong>Claim it.</strong> {@code ON CONFLICT DO NOTHING}; the rowcount is the answer. Zero
 *       means another replica, or an earlier delivery of the same event, already has it.
 *   <li><strong>Publish synchronously.</strong> The contract of this method is that a failed
 *       settlement becomes a non-2xx, and an asynchronous listener's failure cannot be reported.
 *   <li><strong>Release on failure.</strong> A claim must not survive the failure of the work it
 *       guarded (ADR-038): otherwise the redelivery is dismissed as a duplicate and the buyer's
 *       charge never reaches an order.
 * </ol>
 */
@Slf4j
@Service
public class PaymentWebhookService {

    private static final String SETTLED = "payment_intent.succeeded";
    private static final String HOLD_TOKEN_METADATA = "holdToken";

    private final WebhookEventStore webhookEvents;
    private final PaymentTransactionStore transactions;
    private final ApplicationEventPublisher events;
    private final PaymentProperties properties;

    private final MeterRegistry meters;

    public PaymentWebhookService(
            WebhookEventStore webhookEvents,
            PaymentTransactionStore transactions,
            ApplicationEventPublisher events,
            PaymentProperties properties,
            MeterRegistry meters) {
        this.webhookEvents = webhookEvents;
        this.transactions = transactions;
        this.events = events;
        this.properties = properties;
        this.meters = meters;
    }

    /**
     * @param rawBody the request body <em>exactly</em> as received. Anything that reshapes it —
     *     parsing to a DTO and re-serialising, most obviously — invalidates the signature, because
     *     the signature is over the bytes and not over the meaning.
     */
    public void handle(String rawBody, String signature) {
        Event event = verify(rawBody, signature);
        webhookReceived(event.getType());

        if (!SETTLED.equals(event.getType())) {
            log.debug("Ignoring webhook {} of type {}", event.getId(), event.getType());
            return;
        }

        PaymentIntent intent = intentOf(event);
        String holdToken = holdTokenOf(intent);
        if (holdToken == null) {
            // Not ours, or minted before this metadata existed. Acknowledge it: there is nothing to
            // settle, and a redelivery would only reach this same conclusion for ever.
            log.warn("Webhook {} carries intent {} with no holdToken metadata", event.getId(), intent.getId());
            return;
        }

        if (!webhookEvents.claim(event.getId(), event.getType(), intent.getId(), holdToken)) {
            log.info("Webhook {} is already claimed — acknowledging the replay", event.getId());
            return;
        }

        try {
            events.publishEvent(new PaymentSettledEvent(
                    holdToken,
                    intent.getId(),
                    transactions.referenceForGateway(intent.getId()).orElse(null),
                    intent.getAmount() == null ? 0L : intent.getAmount(),
                    intent.getCurrency() == null ? null : intent.getCurrency().toUpperCase()));

            // Inside the same guard. If stamping the claim fails, the settlement itself is still in
            // doubt from this method's point of view, and the honest answer is to give the claim
            // back and let the provider ask again — a second delivery finds the order already
            // CONFIRMED and does nothing.
            webhookEvents.markProcessed(event.getId());

        } catch (RuntimeException settlementFailed) {
            webhookEvents.release(event.getId());
            log.error(
                    "Settlement of webhook {} failed; the claim was released so a redelivery can retry",
                    event.getId(),
                    settlementFailed);
            throw settlementFailed;
        }
    }

    private Event verify(String rawBody, String signature) {
        try {
            return Webhook.constructEvent(rawBody, signature, properties.getStripe().getWebhookSecret());
        } catch (SignatureVerificationException | IllegalArgumentException rejected) {
            throw PaymentErrors.webhookSignatureInvalid(rejected);
        }
    }

    private static String holdTokenOf(PaymentIntent intent) {
        if (intent.getMetadata() == null) {
            return null;
        }
        String holdToken = intent.getMetadata().get(HOLD_TOKEN_METADATA);
        return holdToken == null || holdToken.isBlank() ? null : holdToken;
    }

    /**
     * Pulls the {@link PaymentIntent} out of the event.
     *
     * <p>Falls back to {@code deserializeUnsafe} because {@code getObject()} returns empty whenever
     * a delivery's API version differs from the one this library was built against — which is
     * routine rather than exceptional, since the provider stamps each account's own version into
     * every payload. The four fields read here (id, amount, currency, metadata) have been stable
     * across every version that exists, so "unsafe" is the narrower risk by a wide margin: the
     * alternative is dropping a settlement whose money has already moved.
     */
    private PaymentIntent intentOf(Event event) {
        EventDataObjectDeserializer deserializer = event.getDataObjectDeserializer();
        StripeObject object = deserializer.getObject().orElseGet(() -> {
            try {
                return deserializer.deserializeUnsafe();
            } catch (Exception undeserializable) {
                throw new IllegalStateException(
                        "Webhook " + event.getId() + " carried an unreadable data object",
                        undeserializable);
            }
        });
        if (object instanceof PaymentIntent intent) {
            return intent;
        }
        throw new IllegalStateException(
                "Webhook " + event.getId() + " of type " + event.getType() + " is not a PaymentIntent");
    }
    private void webhookReceived(String eventType) {
        Counter.builder("flashseats.payment.webhook.received")
                .description("Verified Stripe webhooks received by event type")
                .tag("type", eventType)
                .register(meters)
                .increment();
    }
}
