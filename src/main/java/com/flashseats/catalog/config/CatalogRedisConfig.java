package com.flashseats.catalog.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The two scripts that move the live stock counter.
 *
 * <p>Loaded once at startup rather than inlined at the call site: Spring's script executor caches the
 * SHA and falls back to a full {@code EVAL} on {@code NOSCRIPT}, so a Redis restart re-registers them
 * without anyone noticing.
 */
@Configuration
public class CatalogRedisConfig {

    @Bean
    public RedisScript<Long> stockReserveScript() {
        return script("redis/stock_reserve.lua");
    }

    @Bean
    public RedisScript<Long> stockRestoreScript() {
        return script("redis/stock_restore.lua");
    }

    private static RedisScript<Long> script(String path) {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource(path));
        script.setResultType(Long.class);
        return script;
    }
}
