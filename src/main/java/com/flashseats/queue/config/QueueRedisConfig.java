package com.flashseats.queue.config;

import com.flashseats.shared.redis.LuaScript;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The queue's scripts: the cluster-wide admission budget (ADR-049), one session's state, and a
 * batch of promotions (ADR-079).
 *
 * <p>Read once at startup, never per call (ADR-079), and loaded from the classpath rather than
 * inlined, like catalog's two. The last two replaced pipelines: a script runs on the shared
 * connection, and a pipeline takes a connection of its own — with no pool, a new one per call.
 */
@Configuration
public class QueueRedisConfig {

    @Bean
    public RedisScript<Long> promotionBudgetScript() {
        return LuaScript.load("redis/promotion_budget.lua", Long.class);
    }

    @Bean
    public RedisScript<Long> promotionScript() {
        return LuaScript.load("redis/queue_promote.lua", Long.class);
    }

    @Bean
    @SuppressWarnings("unchecked")
    public RedisScript<List<Object>> queueStateScript() {
        return LuaScript.load("redis/queue_state.lua", (Class<List<Object>>) (Class<?>) List.class);
    }
}
