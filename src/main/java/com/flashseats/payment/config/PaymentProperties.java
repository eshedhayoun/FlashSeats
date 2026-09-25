package com.flashseats.payment.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Payment tunables. */
@ConfigurationProperties(prefix = "flashseats.payment")
@Getter
@Setter
public class PaymentProperties {

    /**
     * How long the duplicate-click guard holds a hold token, roughly one gateway timeout (ADR-014).
     *
     * <p>Deliberately short. An earlier design used 24 hours, which meant a crash mid-charge locked
     * that buyer out for a day.
     */
    private int inflightTtlSeconds = 90;

    private final Stripe stripe = new Stripe();

    private final Breaker breaker = new Breaker();

    /**
     * The real provider.
     *
     * <p>{@code enabled} defaults to <strong>false</strong>, which is what keeps a clean checkout
     * working with no configuration: {@code dev}, {@code test} and the load harness run the stub,
     * which drives every branch of the checkout sequence — decline, outage, 3-D Secure — without
     * keys or network.
     *
     * <p>{@code webhookSecret} is read on <em>every</em> profile, independently of {@code enabled}.
     * Signature verification is the webhook receiver's front door and the integration tests sign
     * their own payloads with this value, so gating it on {@code enabled} would leave the endpoint
     * untested on exactly the configuration the tests run.
     */
    @Getter
    @Setter
    public static class Stripe {

        private boolean enabled = false;
        private String apiKey = "";
        private String webhookSecret = "whsec_dev_only_change_me";
        private int connectTimeoutMillis = 5_000;
        private int readTimeoutMillis = 20_000;
    }

    /**
     * The circuit breaker around the gateway.
     *
     * <p>Counted over calls rather than time because the interesting quantity is "how many of the
     * last N charges failed to reach the provider", and a sale's request rate varies by three orders
     * of magnitude between a quiet minute and the first second of a drop.
     */
    @Getter
    @Setter
    public static class Breaker {

        private int slidingWindowSize = 20;
        private int minimumNumberOfCalls = 20;
        private float failureRateThresholdPercent = 50;
        private int waitInOpenStateSeconds = 30;
        private int permittedCallsInHalfOpenState = 3;
    }
}
