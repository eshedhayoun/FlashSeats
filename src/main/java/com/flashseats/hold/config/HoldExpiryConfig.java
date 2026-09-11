package com.flashseats.hold.config;

import com.flashseats.hold.service.HoldExpiryListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Subscribes this replica to Redis key-expiry events.
 *
 * <p><strong>Its own container, not {@code queue}'s.</strong> {@code QueuePubSubConfig} declares one
 * for promotion fan-out, and reusing it would mean this module reaching into another's
 * {@code config} package — a boundary violation {@code ApplicationModules.verify()} exists to catch.
 * A second container is a handful of connections, which is the correct price.
 *
 * <p>A {@link ChannelTopic}, not a pattern. {@code __keyevent@<db>__:expired} is a single channel
 * carrying every expiring key in the database, so there is nothing to pattern-match on — the
 * filtering happens in {@link HoldExpiryListener}, by key prefix, because Redis offers no way to do
 * it server-side.
 *
 * <p>This requires {@code notify-keyspace-events Ex} on the server, which
 * {@code docker/redis/redis.conf} sets. Without it the subscription succeeds, no event ever arrives,
 * and expiry silently falls back to the sweeper — correct, just slower. That is the right failure
 * mode for a component whose entire job is speed, and it is why nothing here asserts the setting.
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
