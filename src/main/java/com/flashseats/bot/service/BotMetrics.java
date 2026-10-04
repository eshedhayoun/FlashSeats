package com.flashseats.bot.service;

import com.flashseats.bot.model.BotOutcome;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Live metrics for bot-defence refusals.
 *
 * <p>These counters are incremented on the request path, before asynchronous audit persistence.
 * That means the metric reflects traffic immediately and is not affected by audit-queue drops.
 */
@Service
public class BotMetrics {

    private final Map<BotOutcome, Counter> refusalCounters;
    private final Counter limiterUnavailable;

    public BotMetrics(MeterRegistry meters) {
        this.refusalCounters = new EnumMap<>(BotOutcome.class);

        register(meters, BotOutcome.RATE_LIMITED);
        register(meters, BotOutcome.IP_BLOCKED);
        register(meters, BotOutcome.VERIFICATION_FAILED);
        this.limiterUnavailable = Counter.builder("flashseats.bot.limiter.unavailable")
                .description("Requests let through because the rate-limit buckets could not be read (ADR-067)")
                .register(meters);
    }

    /** The limiter failed open: any non-zero rate means rate limiting is off right now. */
    public void recordLimiterUnavailable() {
        limiterUnavailable.increment();
    }

    public void recordRefusal(BotOutcome outcome) {
        Counter counter = refusalCounters.get(outcome);
        if (counter != null) {
            counter.increment();
        }
    }

    private void register(MeterRegistry meters, BotOutcome outcome) {
        refusalCounters.put(
                outcome,
                Counter.builder("flashseats.bot.refusals")
                        .description("Bot-defence requests refused")
                        .tag("outcome", outcome.name())
                        .register(meters));
    }
}