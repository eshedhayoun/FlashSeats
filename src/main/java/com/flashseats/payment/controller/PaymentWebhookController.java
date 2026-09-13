package com.flashseats.payment.controller;

import com.flashseats.payment.service.PaymentWebhookService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payment provider's callback. The only endpoint this module has.
 *
 * <p><strong>{@code String}, not a DTO.</strong> The signature is computed over the exact bytes of
 * the body; binding to an object and letting Jackson re-serialise it changes key order, whitespace
 * and number formatting, and the signature then fails on every legitimate delivery. The raw body is
 * handed to the service unmodified and parsed only after it has been verified.
 *
 * <p>Deliberately <strong>not</strong> exempt from rate limiting. An exempt endpoint is an unmetered
 * one ({@code docs/modules/bot.md} §6), and the IP bucket — 300 burst, 150/s — sits orders of
 * magnitude above any real delivery rate.
 *
 * <p>Nginx gives this path {@code proxy_next_upstream off}: a replayed delivery is safe, because
 * {@code webhook_events} dedupes on the provider's event id, but it is pointless — the provider
 * redelivers on any non-2xx, and more patiently than a load balancer would.
 */
@RestController
@RequestMapping("/api/v1/payments")
public class PaymentWebhookController {

    private final PaymentWebhookService webhooks;

    public PaymentWebhookController(PaymentWebhookService webhooks) {
        this.webhooks = webhooks;
    }

    /**
     * Accepts one delivery.
     *
     * <p>{@code 200} means "do not send this again", and is therefore the answer to a replay and to
     * an event type we ignore, as well as to a settlement that worked. Anything that throws becomes
     * a non-2xx and asks for a redelivery — which the released claim makes safe.
     */
    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> receive(
            @RequestBody String rawBody, @RequestHeader("Stripe-Signature") String signature) {

        webhooks.handle(rawBody, signature);
        return ResponseEntity.ok().build();
    }
}
