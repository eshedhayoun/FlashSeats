package com.flashseats.order.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/**
 * Stage timings for checkout. The stage tag is deliberately a bounded set: these meters are used
 * to identify the remote call that dominates the load drill, not to record buyer or order data.
 */
@Component
public class CheckoutMetrics {

    private final Map<String, Timer> timers;

    public CheckoutMetrics(MeterRegistry meters) {
        this.timers = Map.of(
                "receipt_lookup", timer(meters, "receipt_lookup"),
                "hold_lookup", timer(meters, "hold_lookup"),
                "tier_lookup", timer(meters, "tier_lookup"),
                "order_find_or_create", timer(meters, "order_find_or_create"),
                "grace_extension", timer(meters, "grace_extension"),
                "payment_authorize", timer(meters, "payment_authorize"),
                "order_confirm", timer(meters, "order_confirm"),
                "failed_attempt", timer(meters, "failed_attempt"),
                "competing_receipt_lookup", timer(meters, "competing_receipt_lookup"));
    }

    public <T> T record(String stage, Supplier<T> operation) {
        Timer.Sample sample = Timer.start();
        try {
            return operation.get();
        } finally {
            Timer timer = timers.get(stage);
            if (timer == null) {
                throw new IllegalArgumentException("Unknown checkout timing stage: " + stage);
            }
            sample.stop(timer);
        }
    }

    public void record(String stage, Runnable operation) {
        record(
                stage,
                () -> {
                    operation.run();
                    return null;
                });
    }

    private static Timer timer(MeterRegistry meters, String stage) {
        return Timer.builder("flashseats.checkout.stage")
                .description("Checkout stage duration")
                .tag("stage", stage)
                .publishPercentiles(0.5, 0.95, 0.99)
                .register(meters);
    }
}
