package com.flashseats.shared.time;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the one {@link Clock} every module reads time from.
 *
 * <p>The server owns the clock: sale windows, hold expiry, pass and admission TTLs and the
 * {@code serverTime} the SPA counts down against all derive from this bean (ADR-016). Injecting it
 * rather than calling {@code Instant.now()} is what makes the timers testable — a test can advance
 * five minutes without sleeping for five minutes.
 */
@Configuration
public class ClockConfig {

    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
