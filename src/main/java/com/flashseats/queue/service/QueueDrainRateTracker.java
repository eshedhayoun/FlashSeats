package com.flashseats.queue.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Deque;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import org.springframework.stereotype.Component;

/**
 * Measures how fast a queue is actually draining, over a sliding window.
 *
 * <p>Wait estimates are derived from this rather than from {@code position × assumedServiceTime}
 * (ADR-026). Measuring the real rate accounts for abandonment implicitly: buyers who walk away still
 * reach the front and are promoted, so they show up in the drain rate without anyone having to guess
 * how many there are.
 *
 * <p>Per-replica and in memory on purpose. An estimate does not need to be shared, and a wrong
 * estimate is a cosmetic problem — the queue's correctness does not depend on it.
 */
@Component
public class QueueDrainRateTracker {

    private static final Duration WINDOW = Duration.ofSeconds(30);

    private final Map<Long, Deque<Sample>> samplesByEvent = new ConcurrentHashMap<>();

    public void record(long eventId, long depth, Instant at) {
        Deque<Sample> samples = samplesByEvent.computeIfAbsent(eventId, id -> new ConcurrentLinkedDeque<>());
        samples.addLast(new Sample(at, depth));

        Instant cutoff = at.minus(WINDOW);
        Sample oldest;
        while ((oldest = samples.peekFirst()) != null && oldest.at().isBefore(cutoff)) {
            samples.removeFirst();
        }
    }

    /** Sessions leaving the queue per second, or empty if there is not yet enough history. */
    public OptionalDouble perSecond(long eventId) {
        Deque<Sample> samples = samplesByEvent.get(eventId);
        if (samples == null || samples.size() < 2) {
            return OptionalDouble.empty();
        }
        Sample oldest = samples.peekFirst();
        Sample newest = samples.peekLast();
        if (oldest == null || newest == null) {
            return OptionalDouble.empty();
        }
        double seconds = Duration.between(oldest.at(), newest.at()).toMillis() / 1000d;
        double drained = oldest.depth() - newest.depth();
        if (seconds <= 0 || drained <= 0) {
            return OptionalDouble.empty();
        }
        return OptionalDouble.of(drained / seconds);
    }

    /**
     * Estimated seconds until this position reaches the front, rounded up. Empty while the rate is
     * unknown — the UI should say "calculating" rather than invent a number it will have to revise.
     */
    public OptionalDouble estimateSeconds(long eventId, int position) {
        OptionalDouble rate = perSecond(eventId);
        return rate.isPresent() ? OptionalDouble.of(position / rate.getAsDouble()) : OptionalDouble.empty();
    }

    private record Sample(Instant at, long depth) {}
}
