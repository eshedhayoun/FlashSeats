package com.flashseats.payment.controller;

import com.flashseats.payment.service.PaymentWebhookService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payment provider's callback, and this module's only endpoint.
 *
 * <p>The body is a {@code String}, not a DTO: the signature covers the exact bytes, and a Jackson
 * round trip changes key order and whitespace, so every legitimate delivery would fail. Not exempt
 * from rate limiting; the IP bucket sits far above any real delivery rate.
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
    // No `consumes`: a content type we did not predict would answer 415, and the provider retries
    // every non-2xx — for ever, against a request that will never be accepted. The signature is the
    // gate here, not the header.
    @PostMapping("/webhook")
    public ResponseEntity<Void> receive(
            @RequestBody String rawBody, @RequestHeader("Stripe-Signature") String signature) {

        webhooks.handle(rawBody, signature);
        return ResponseEntity.ok().build();
    }
}
