package com.flashseats.hold.config;

import com.flashseats.hold.service.HoldExpiryListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Subscribes this replica to Redis key-expiry events. It has its own listener container, because
 * reusing {@code queue}'s would reach into another module's {@code config}. It uses a
 * {@link ChannelTopic}: {@code __keyevent@<db>__:expired} is one channel for every key, filtered by
 * prefix in {@link HoldExpiryListener}.
 *
 * <p>Needs {@code notify-keyspace-events Ex} (set in {@code redis.conf}). Without it nothing arrives
 * and expiry falls back to the sweeper: correct, only slower.
 */
@Configuration
public class HoldExpiryConfig {

    /**
     * The channel name embeds the database index, so it has to follow
     * {@code spring.data.redis.database} rather than assume 0. Pointed at the wrong database this
     * would subscribe successfully and receive nothing at all.
     */
    @Bean
    public RedisMessageListenerContainer holdExpiryListenerContainer(
            RedisConnectionFactory connectionFactory,
            HoldExpiryListener listener,
            @Value("${spring.data.redis.database:0}") int database) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic("__keyevent@" + database + "__:expired"));
        return container;
    }
}
