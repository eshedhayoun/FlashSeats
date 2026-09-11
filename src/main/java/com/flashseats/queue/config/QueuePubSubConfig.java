package com.flashseats.queue.config;

import com.flashseats.queue.service.QueuePubSubListener;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Subscribes this replica to every event's queue channel.
 *
 * <p>A pattern subscription rather than one per event, so a sale that opens after startup is covered
 * without anyone having to remember to subscribe to it.
 */
@Configuration
public class QueuePubSubConfig {

    @Bean
    public RedisMessageListenerContainer queueMessageListenerContainer(
            RedisConnectionFactory connectionFactory, QueuePubSubListener listener) {

        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new PatternTopic("queue:events:*"));
        return container;
    }
}
