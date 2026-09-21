package com.flashseats.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Payment tunables. */
@ConfigurationProperties(prefix = "flashseats.payment")
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

    public int getInflightTtlSeconds() {
        return inflightTtlSeconds;
    }

    public void setInflightTtlSeconds(int inflightTtlSeconds) {
        this.inflightTtlSeconds = inflightTtlSeconds;
    }

    public Stripe getStripe() {
        return stripe;
    }

    public Breaker getBreaker() {
        return breaker;
    }

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
    public static class Stripe {

        private boolean enabled = false;
        private String apiKey = "";
        private String webhookSecret = "whsec_dev_only_change_me";
        private int connectTimeoutMillis = 5_000;
        private int readTimeoutMillis = 20_000;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public String getWebhookSecret() {
            return webhookSecret;
        }

        public void setWebhookSecret(String webhookSecret) {
            this.webhookSecret = webhookSecret;
        }

        public int getConnectTimeoutMillis() {
            return connectTimeoutMillis;
        }

        public void setConnectTimeoutMillis(int connectTimeoutMillis) {
            this.connectTimeoutMillis = connectTimeoutMillis;
        }

        public int getReadTimeoutMillis() {
            return readTimeoutMillis;
        }

        public void setReadTimeoutMillis(int readTimeoutMillis) {
            this.readTimeoutMillis = readTimeoutMillis;
        }
    }

    /**
     * The circuit breaker around the gateway.
     *
     * <p>Counted over calls rather than time because the interesting quantity is "how many of the
     * last N charges failed to reach the provider", and a sale's request rate varies by three orders
     * of magnitude between a quiet minute and the first second of a drop.
     */
    public static class Breaker {

        private int slidingWindowSize = 20;
        private int minimumNumberOfCalls = 20;
        private float failureRateThresholdPercent = 50;
        private int waitInOpenStateSeconds = 30;
        private int permittedCallsInHalfOpenState = 3;

        public int getSlidingWindowSize() {
            return slidingWindowSize;
        }

        public void setSlidingWindowSize(int slidingWindowSize) {
            this.slidingWindowSize = slidingWindowSize;
        }

        public int getMinimumNumberOfCalls() {
            return minimumNumberOfCalls;
        }

        public void setMinimumNumberOfCalls(int minimumNumberOfCalls) {
            this.minimumNumberOfCalls = minimumNumberOfCalls;
        }

        public float getFailureRateThresholdPercent() {
            return failureRateThresholdPercent;
        }

        public void setFailureRateThresholdPercent(float failureRateThresholdPercent) {
            this.failureRateThresholdPercent = failureRateThresholdPercent;
        }

        public int getWaitInOpenStateSeconds() {
            return waitInOpenStateSeconds;
        }

        public void setWaitInOpenStateSeconds(int waitInOpenStateSeconds) {
            this.waitInOpenStateSeconds = waitInOpenStateSeconds;
        }

        public int getPermittedCallsInHalfOpenState() {
            return permittedCallsInHalfOpenState;
        }

        public void setPermittedCallsInHalfOpenState(int permittedCallsInHalfOpenState) {
            this.permittedCallsInHalfOpenState = permittedCallsInHalfOpenState;
        }
    }
}
