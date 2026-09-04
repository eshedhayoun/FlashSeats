package com.flashseats.order.config;

import com.flashseats.order.service.LoggingOutboxPublisher;
import com.flashseats.order.service.OutboxPublisher;
import com.flashseats.order.service.RabbitOutboxPublisher;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses where the outbox drains to.
 *
 * <p>Two transports, one property. {@code rabbit} is the real one; {@code log} lets the whole
 * checkout path — including the three-transaction relay and the stale-claim sweep — run and be
 * tested against PostgreSQL alone, with no broker in the picture.
 *
 * <p>Neither choice reaches {@code order}'s own code: the relay talks to the interface.
 */
@Configuration
public class OutboxPublisherConfig {

    @Bean
    @ConditionalOnProperty(name = "flashseats.outbox.transport", havingValue = "rabbit", matchIfMissing = true)
    public OutboxPublisher rabbitOutboxPublisher(RabbitTemplate rabbitTemplate) {
        return new RabbitOutboxPublisher(rabbitTemplate);
    }

    @Bean
    @ConditionalOnProperty(name = "flashseats.outbox.transport", havingValue = "log")
    public OutboxPublisher loggingOutboxPublisher() {
        return new LoggingOutboxPublisher();
    }
}
