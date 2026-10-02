package com.flashseats.queue.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.function.Supplier;
import org.springframework.stereotype.Component;

/** Timers for the queue read path, whose latency is visible to waiting-room clients. */
@Component
public class QueueMetrics {

    private final Timer statusTimer;
    private final Timer redisReadTimer;
    private final Timer redisCallbackTimer;
    private final Timer redisDecodeTimer;

    public QueueMetrics(MeterRegistry meters) {
        this.statusTimer = timer(
                meters,
                "flashseats.queue.status",
                "Queue status request duration");
        this.redisReadTimer = timer(
                meters,
                "flashseats.queue.redis.read",
                "Queue status Redis snapshot duration");
        this.redisCallbackTimer = timer(
                meters,
                "flashseats.queue.redis.callback",
                "Queue status Redis command dispatch duration");
        this.redisDecodeTimer = timer(
                meters,
                "flashseats.queue.redis.decode",
                "Queue status Redis reply decoding duration");
    }

    public <T> T timeStatus(Supplier<T> operation) {
        return timed(statusTimer, operation);
    }

    public <T> T timeRedisRead(Supplier<T> operation) {
        return timed(redisReadTimer, operation);
    }

    public <T> T timeRedisCallback(Supplier<T> operation) {
        return timed(redisCallbackTimer, operation);
    }

    public <T> T timeRedisDecode(Supplier<T> operation) {
        return timed(redisDecodeTimer, operation);
    }

    private static <T> T timed(Timer timer, Supplier<T> operation) {
        Timer.Sample sample = Timer.start();
        try {
            return operation.get();
        } finally {
            sample.stop(timer);
        }
    }

    private static Timer timer(MeterRegistry meters, String name, String description) {
        return Timer.builder(name)
                .description(description)
                .register(meters);
    }
}
