package com.flashseats.flashseats.support;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Builds and signs provider webhook payloads, the way the provider does.
 *
 * <p>The signature is computed here rather than mocked, over the exact bytes that are then sent —
 * which is the only way to test the one gate the webhook endpoint has. A test that stubbed out
 * verification would leave the endpoint unauthenticated in exactly the way that matters.
 *
 * <p>{@code API_VERSION} is deliberately an <em>old</em> one. The provider stamps each account's own
 * version into every delivery, so in production the library's typed deserialiser routinely refuses
 * the payload and the receiver falls back to {@code deserializeUnsafe}. Signing with a stale version
 * here means the tests exercise that fallback rather than the path production will rarely take.
 */
public final class StripeWebhooks {

    /** Matches {@code flashseats.payment.stripe.webhook-secret}'s default, used by dev and test. */
    public static final String TEST_SECRET = "whsec_dev_only_change_me";

    private static final String API_VERSION = "2020-08-27";

    private StripeWebhooks() {}

    /** A {@code payment_intent.succeeded} body for a charge against {@code holdToken}. */
    public static String settledBody(
            String eventId, String paymentIntentId, String holdToken, long amountCents) {
        return """
               {
                 "id": "%s",
                 "object": "event",
                 "api_version": "%s",
                 "created": %d,
                 "type": "payment_intent.succeeded",
                 "data": {
                   "object": {
                     "id": "%s",
                     "object": "payment_intent",
                     "amount": %d,
                     "currency": "usd",
                     "status": "succeeded",
                     "metadata": { "holdToken": "%s", "orderNumber": "TK-WEBHOOK" }
                   }
                 }
               }
               """
                .formatted(
                        eventId,
                        API_VERSION,
                        Instant.now().getEpochSecond(),
                        paymentIntentId,
                        amountCents,
                        holdToken);
    }

    /** A body of a type the receiver must acknowledge and ignore. */
    public static String unhandledBody(String eventId) {
        return """
               {
                 "id": "%s",
                 "object": "event",
                 "api_version": "%s",
                 "created": %d,
                 "type": "charge.updated",
                 "data": { "object": { "id": "ch_test", "object": "charge" } }
               }
               """
                .formatted(eventId, API_VERSION, Instant.now().getEpochSecond());
    }

    /**
     * The {@code Stripe-Signature} header for a body.
     *
     * <p>{@code t=<unix>,v1=<hex hmac-sha256 of "t.body">}. The timestamp is inside the signed bytes
     * precisely so an old delivery cannot be replayed against a later moment, which is why it is
     * generated fresh rather than fixed.
     */
    public static String signature(String body, String secret) {
        long timestamp = Instant.now().getEpochSecond();
        return "t=" + timestamp + ",v1=" + hmacSha256(secret, timestamp + "." + body);
    }

    public static String signature(String body) {
        return signature(body, TEST_SECRET);
    }

    private static String hmacSha256(String secret, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception impossible) {
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }
}
