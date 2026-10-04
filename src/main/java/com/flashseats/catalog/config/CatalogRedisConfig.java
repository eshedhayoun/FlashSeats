package com.flashseats.catalog.config;

import com.flashseats.shared.redis.LuaScript;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.script.RedisScript;

/**
 * The two scripts that move the live stock counter.
 *
 * <p>Read once at startup rather than inlined at the call site, and never re-read per call (ADR-079):
 * Spring's script executor sends the SHA and falls back to a full {@code EVAL} on {@code NOSCRIPT},
 * so a Redis restart re-registers them without anyone noticing.
 */
@Configuration
public class CatalogRedisConfig {

    @Bean
    public RedisScript<Long> stockReserveScript() {
        return LuaScript.load("redis/stock_reserve.lua", Long.class);
    }

    @Bean
    public RedisScript<Long> stockRestoreScript() {
        return LuaScript.load("redis/stock_restore.lua", Long.class);
    }
}
