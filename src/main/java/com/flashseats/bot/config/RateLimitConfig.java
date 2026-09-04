package com.flashseats.bot.config;

import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

/**
 * Wires Bucket4j to the same Redis the rest of the system uses.
 *
 * <p><strong>Redis-backed, not in-memory.</strong> In-memory buckets across three replicas silently
 * triple every configured limit, which is the kind of bug that only shows up in production under the
 * exact load the limits exist to control (ADR-011).
 */
@Configuration
public class RateLimitConfig {

    @Bean
    public ProxyManager<byte[]> rateLimitProxyManager(RedisConnectionFactory connectionFactory) {
        if (!(connectionFactory instanceof LettuceConnectionFactory lettuce)) {
            throw new IllegalStateException(
                    "Rate limiting requires the Lettuce Redis driver; found "
                            + connectionFactory.getClass().getName());
        }
        RedisClient client = (RedisClient) lettuce.getNativeClient();
        return Bucket4jLettuce.casBasedBuilder(client).build();
    }
}
