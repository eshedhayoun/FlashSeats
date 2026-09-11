package com.flashseats.order.config;

import com.flashseats.order.service.LoggingOutboxPublisher;
import com.flashseats.order.service.OutboxPublisher;
import com.flashseats.order.service.RabbitOutboxPublisher;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Configuration
public class OutboxPublisherConfig {

    @Bean
    @ConditionalOnProperty(name = "flashseats.outbox.transport", havingValue = "rabbit", matchIfMissing = true)
    public OutboxPublisher rabbitOutboxPublisher(
            RabbitTemplate rabbitTemplate, OutboxProperties properties) {

        // An unroutable message is handled per-message by the publisher, which reads
        // CorrelationData.getReturned(). Registering a callback here anyway: without one
        // RabbitTemplate logs "Returned message but no callback available" on every return, which
        // reads like the return was dropped at exactly the moment someone is investigating why a
        // ticket was not delivered. The correlation is still populated first, so this changes
        // nothing but the log.
        rabbitTemplate.setReturnsCallback(returned ->
                log.debug(
                        "Broker returned {} ({}); the publisher will leave it unconfirmed",
                        returned.getMessage().getMessageProperties().getMessageId(),
                        returned.getReplyText()));

        return new RabbitOutboxPublisher(
                rabbitTemplate, Duration.ofMillis(properties.getConfirmTimeoutMs()));
    }

    @Bean
    @ConditionalOnProperty(name = "flashseats.outbox.transport", havingValue = "log")
    public OutboxPublisher loggingOutboxPublisher() {
        return new LoggingOutboxPublisher();
    }
}
