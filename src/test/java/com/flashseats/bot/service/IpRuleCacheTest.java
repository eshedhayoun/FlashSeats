package com.flashseats.bot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flashseats.bot.config.BotProperties;
import com.flashseats.bot.model.IpRule;
import com.flashseats.bot.model.IpRuleAction;
import com.flashseats.bot.repository.IpRuleRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How the address list behaves when it is <em>not</em> working.
 *
 * <p>Everything asserted here is invisible in an integration test, because none of it is an error —
 * it is load, arriving at the worst possible moment, from the one component whose job is to keep
 * load off the system. A filter that queries the database per request during an outage does not
 * fail; it makes the outage worse and looks innocent doing it.
 */
@DisplayName("The ip_rules snapshot reloads once per window, and once at a time")
class IpRuleCacheTest {

    private static final long TTL_MS = 10_000;

    @Test
    @DisplayName("A database that is down is asked once per TTL, not once per request")
    void aFailingLoadBacksOff() {
        IpRuleRepository repository = mock(IpRuleRepository.class);
        AtomicInteger reads = new AtomicInteger();
        when(repository.findAll()).thenAnswer(call -> {
            reads.incrementAndGet();
            throw new IllegalStateException("connection refused");
        });

        MovableClock clock = new MovableClock(Instant.parse("2026-09-13T10:00:00Z"));
        IpRuleService rules = new IpRuleService(repository, properties(), clock);

        for (int i = 0; i < 50; i++) {
            assertThat(rules.actionFor("10.0.0.1")).isNull();
        }

        // The failure path has to stamp the attempt exactly like a success. Without that, every one
        // of these fifty requests opens a connection against a database that is already down —
        // the load-shedder becoming the load, during the incident it exists to survive.
        assertThat(reads.get()).isEqualTo(1);

        clock.advanceMillis(TTL_MS + 1);
        assertThat(rules.actionFor("10.0.0.1")).isNull();
        assertThat(reads.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("A cold start with no rules at all still costs one query per window")
    void anEmptyTableIsNotReloadedPerRequest() {
        IpRuleRepository repository = mock(IpRuleRepository.class);
        AtomicInteger reads = new AtomicInteger();
        when(repository.findAll()).thenAnswer(call -> {
            reads.incrementAndGet();
            return List.of();
        });

        IpRuleService rules = new IpRuleService(
                repository, properties(), new MovableClock(Instant.parse("2026-09-13T10:00:00Z")));

        for (int i = 0; i < 50; i++) {
            assertThat(rules.actionFor("10.0.0.1")).isNull();
        }

        // "No rules" is the normal state of this table, and an empty result must be as cacheable as
        // a full one — otherwise the common case is the expensive one.
        assertThat(reads.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("Many threads arriving at an expiry produce one reload, not one each")
    void concurrentReadersReloadOnce() throws Exception {
        IpRuleRepository repository = mock(IpRuleRepository.class);
        AtomicInteger reads = new AtomicInteger();
        CountDownLatch inLoader = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        when(repository.findAll()).thenAnswer(call -> {
            if (reads.incrementAndGet() == 1) {
                inLoader.countDown();
                release.await(5, TimeUnit.SECONDS);
            }
            return List.of();
        });

        IpRuleService rules = new IpRuleService(
                repository, properties(), new MovableClock(Instant.parse("2026-09-13T10:00:00Z")));

        Thread first = Thread.ofVirtual().start(() -> rules.actionFor("10.0.0.1"));
        assertThat(inLoader.await(5, TimeUnit.SECONDS)).isTrue();

        // Everyone arriving while the first load is in flight serves the stale snapshot. Without the
        // single-flight guard, a few thousand requests a second would each issue their own query at
        // every TTL boundary — a pool spike on a timer.
        CountDownLatch others = new CountDownLatch(20);
        for (int i = 0; i < 20; i++) {
            Thread.ofVirtual().start(() -> {
                rules.actionFor("10.0.0.1");
                others.countDown();
            });
        }
        assertThat(others.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(reads.get()).isEqualTo(1);

        release.countDown();
        first.join();
    }

    @Test
    @DisplayName("A rule stops applying at ITS expiry, not at the cache's")
    void expiryIsDerivedFromTheClock() {
        Instant start = Instant.parse("2026-09-13T10:00:00Z");
        IpRuleRepository repository = mock(IpRuleRepository.class);
        when(repository.findAll())
                .thenReturn(List.of(new IpRule(
                        "10.0.0.1", IpRuleAction.DENY, "temporary", start.plusSeconds(2))));

        MovableClock clock = new MovableClock(start);
        IpRuleService rules = new IpRuleService(repository, properties(), clock);

        assertThat(rules.actionFor("10.0.0.1")).isEqualTo(IpRuleAction.DENY);

        // Still well inside the 10 s snapshot TTL. If expiry were baked in at load time, this block
        // would outlive its own deadline by up to a full window — the one kind of staleness nothing
        // downstream can detect (ADR-051).
        clock.advanceMillis(3_000);
        assertThat(rules.actionFor("10.0.0.1")).isNull();
    }

    private static BotProperties properties() {
        BotProperties properties = new BotProperties();
        properties.setIpRuleCacheTtlMs(TTL_MS);
        return properties;
    }

    /** A clock a test can move, so a TTL can be crossed without waiting for one. */
    private static final class MovableClock extends Clock {

        private Instant now;

        private MovableClock(Instant now) {
            this.now = now;
        }

        void advanceMillis(long millis) {
            now = now.plusMillis(millis);
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }
    }
}
