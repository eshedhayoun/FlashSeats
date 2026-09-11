package com.flashseats.order.service;

import com.flashseats.order.model.OutboxEvent;
import java.nio.charset.StandardCharsets;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Publishes outbox rows to the broker.
 *
 * <p>The exchange and routing keys are repeated here rather than imported from {@code notification},
 * on purpose. The two modules are coupled by the <strong>wire format</strong>, not by a Java type:
 * importing a constant would create a compile-time dependency across an asynchronous boundary and
 * make one module's redeploy the other's problem. Two short strings are the honest price of that
 * independence.
 *
 * <p>Called from {@link OutboxRelay} with no transaction open. Messages are persistent, so an order
 * confirmed while the broker restarts still has its ticket queued when it returns.
 */
public class RabbitOutboxPublisher implements OutboxPublisher {

    /** Contract with {@code notification}. Changing either string is a breaking change. */
    private static final String EXCHANGE = "order.events.exchange";

    private static final String ROUTING_CONFIRMED = "order.confirmed";
    private static final String ROUTING_REFUNDED = "order.refunded";

    private final RabbitTemplate rabbit;

    public RabbitOutboxPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    @Override
    public void publish(OutboxEvent event) {
        rabbit.send(EXCHANGE, routingKeyFor(event.getEventType()), toMessage(event));
    }

    private Message toMessage(OutboxEvent event) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding("UTF-8");
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(event.getId().toString());
        return new Message(event.getPayload().getBytes(StandardCharsets.UTF_8), properties);
    }

    private String routingKeyFor(String eventType) {
        return switch (eventType) {
            case OrderCommitService.EVENT_ORDER_REFUNDED -> ROUTING_REFUNDED;
            default -> ROUTING_CONFIRMED;
        };
    }
}
