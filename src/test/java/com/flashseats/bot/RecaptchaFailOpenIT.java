package com.flashseats.bot;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.app.support.BuyerSession;
import com.flashseats.app.support.IntegrationTest;
import com.flashseats.app.support.SaleFixture;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.boot.test.web.server.LocalServerPort;

@DisplayName("reCAPTCHA failure is fail-open without disabling rate limits")
@TestPropertySource(properties = {
        "flashseats.bot.recaptcha.secret=test-secret",
        "flashseats.bot.recaptcha.verify-url=http://127.0.0.1:1/unreachable",
        "flashseats.bot.recaptcha.connect-timeout-ms=100",
        "flashseats.bot.recaptcha.read-timeout-ms=100",
        "flashseats.bot.session-bucket.capacity=5",
        "flashseats.bot.session-bucket.refill-per-second=1",
        "flashseats.bot.ip-bucket.capacity=300",
        "flashseats.bot.ip-bucket.refill-per-second=150"
})
class RecaptchaFailOpenIT extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @BeforeEach
    void seedSale() {
        fixture.reset();

        long eventId = fixture.openEvent("reCAPTCHA Failure Fest");
        fixture.tier(eventId, "GA", 2_500, 20);

        this.eventId = eventId;
    }

    private long eventId;

    @Test
    @DisplayName("An unreachable reCAPTCHA provider allows join, while rate limiting still applies")
    void unreachableRecaptchaFailsOpenButRateLimitStillWorks() {
        BuyerSession buyer = new BuyerSession(port);

        /*
         * reCAPTCHA is enabled because a secret is configured.
         * The provider URL is deliberately unreachable.
         *
         * The join must still succeed because provider transport failures
         * are DEGRADED/fail-open rather than a bot refusal.
         */
        var joined = buyer.post(
                "/queue/join",
                Map.of(
                        "eventId",
                        eventId,
                        "recaptchaToken",
                        "test-token"));

        assertThat(joined.status()).isEqualTo(202);

        /*
         * reCAPTCHA being unavailable must not disable the ordinary
         * session rate limiter.
         */
        List<Integer> statuses = new ArrayList<>();

        for (int i = 0; i < 6; i++) {
            statuses.add(buyer.get("/events/" + eventId).status());
        }

        assertThat(statuses)
                .contains(429);

        var refused = buyer.get("/events/" + eventId);

        assertThat(refused.status()).isEqualTo(429);
        assertThat(refused.errorCode()).isEqualTo("RATE_LIMITED");
        assertThat(refused.json().get("retryable").asBoolean()).isTrue();
    }
}