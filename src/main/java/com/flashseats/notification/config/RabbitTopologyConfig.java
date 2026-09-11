package com.flashseats.notification.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The broker topology.
 *
 * <pre>
 *   order.events.exchange (topic, durable)
 *   ├── order.confirmed ──► notification.order-confirmed.queue
 *   └── order.refunded  ──► notification.order-refunded.queue
 *              │ dead letters
 *              ▼
 *   notification.dlx.exchange (direct) ──► notification.dead-letter.queue
 * </pre>
 *
 * <p>The exchange and routing keys are the contract between {@code order} and this module. They are
 * coupled by the <em>wire format</em>, not by a shared Java type, which is what lets either be
 * changed or redeployed without the other.
 *
 * <p>Everything is durable: an order can be confirmed while the broker is restarting, and the
 * outbox will still be holding that message when it comes back.
 *
 * <p>No message converter is registered. The outbox payload is already a JSON string, so it travels
 * as the message body untouched and the consumer parses it directly — one representation end to end,
 * and no chance of a converter reshaping the contract on the way through.
 */
@Configuration
@ConditionalOnProperty(
        name = "flashseats.notification.enabled", havingValue = "true", matchIfMissing = true)
public class RabbitTopologyConfig {

    public static final String ORDER_EXCHANGE = "order.events.exchange";
    public static final String ROUTING_ORDER_CONFIRMED = "order.confirmed";
    public static final String ROUTING_ORDER_REFUNDED = "order.refunded";

    public static final String QUEUE_ORDER_CONFIRMED = "notification.order-confirmed.queue";
    public static final String QUEUE_ORDER_REFUNDED = "notification.order-refunded.queue";

    private static final String DLX_EXCHANGE = "notification.dlx.exchange";
    private static final String DLQ_ROUTING_KEY = "notification.dead-letter";
    private static final String DLQ_QUEUE = "notification.dead-letter.queue";

    @Bean
    public TopicExchange orderEventsExchange() {
        return new TopicExchange(ORDER_EXCHANGE, true, false);
    }

    @Bean
    public DirectExchange notificationDlxExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    public Queue orderConfirmedQueue() {
        return deadLetteringQueue(QUEUE_ORDER_CONFIRMED);
    }

    @Bean
    public Queue orderRefundedQueue() {
        return deadLetteringQueue(QUEUE_ORDER_REFUNDED);
    }

    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DLQ_QUEUE).build();
    }

    @Bean
    public Binding orderConfirmedBinding() {
        return BindingBuilder.bind(orderConfirmedQueue())
                .to(orderEventsExchange())
                .with(ROUTING_ORDER_CONFIRMED);
    }

    @Bean
    public Binding orderRefundedBinding() {
        return BindingBuilder.bind(orderRefundedQueue())
                .to(orderEventsExchange())
                .with(ROUTING_ORDER_REFUNDED);
    }

    @Bean
    public Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue())
                .to(notificationDlxExchange())
                .with(DLQ_ROUTING_KEY);
    }

    /** A message that cannot be delivered ends up somewhere an operator can find it, not nowhere. */
    private Queue deadLetteringQueue(String name) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(DLX_EXCHANGE)
                .deadLetterRoutingKey(DLQ_ROUTING_KEY)
                .build();
    }
}
