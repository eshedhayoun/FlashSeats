package com.flashseats.queue.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The script behind the cluster-wide admission budget (ADR-049).
 *
 * <p>Loaded from the classpath rather than inlined, like catalog's two: Spring's script executor
 * caches the SHA and falls back to a full {@code EVAL} on {@code NOSCRIPT}, so a Redis restart
 * re-registers it without anyone noticing.
 */
@Configuration
public class QueueRedisConfig {

    @Bean
    public RedisScript<Long> promotionBudgetScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/promotion_budget.lua"));
        script.setResultType(Long.class);
        return script;
    }
}
