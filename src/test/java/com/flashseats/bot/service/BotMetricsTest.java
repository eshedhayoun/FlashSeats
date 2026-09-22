package com.flashseats.bot.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.bot.model.BotOutcome;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class BotMetricsTest {

    @Test
    void recordsEachRefusalOutcomeSeparately() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BotMetrics metrics = new BotMetrics(registry);

        metrics.recordRefusal(BotOutcome.RATE_LIMITED);
        metrics.recordRefusal(BotOutcome.RATE_LIMITED);
        metrics.recordRefusal(BotOutcome.IP_BLOCKED);
        metrics.recordRefusal(BotOutcome.VERIFICATION_FAILED);

        assertThat(registry.get("flashseats.bot.refusals")
                .tag("outcome", "RATE_LIMITED")
                .counter()
                .count())
                .isEqualTo(2.0);

        assertThat(registry.get("flashseats.bot.refusals")
                .tag("outcome", "IP_BLOCKED")
                .counter()
                .count())
                .isEqualTo(1.0);

        assertThat(registry.get("flashseats.bot.refusals")
                .tag("outcome", "VERIFICATION_FAILED")
                .counter()
                .count())
                .isEqualTo(1.0);
    }

        @Test
        void degradedVerificationIsNotCountedAsARefusal() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        BotMetrics metrics = new BotMetrics(registry);

        metrics.recordRefusal(BotOutcome.VERIFICATION_DEGRADED);

        assertThat(
                registry.get("flashseats.bot.refusals")
                        .counter()
                        .count())
                .isZero();

        assertThat(
                registry.get("flashseats.bot.refusals")
                        .counter()
                        .count())
                .isZero();

        assertThat(
                registry.get("flashseats.bot.refusals")
                        .counter()
                        .count())
                .isZero();
        }
}