package com.flashseats.order;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.order.model.OutboxEvent;
import com.flashseats.order.service.RabbitOutboxPublisher;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * The outbox only marks a row {@code PROCESSED} for messages the broker durably kept.
 *
 * <p><strong>Deliberately not a {@code @SpringBootTest}.</strong> {@code RabbitOutboxPublisher} takes
 * a {@code RabbitTemplate} in its constructor, so it can be exercised against a real broker with no
 * application context at all — and that is the point. A second Spring context would mean a second set
 * of {@code @Scheduled} relays and sweepers running against the PostgreSQL and Redis containers that
 * every other test class shares, which is exactly the interference {@code SaleFixture.reset()} warns
 * about: a test that passes alone and fails in a suite.
 *
 * <p>This is also the first coverage {@code RabbitOutboxPublisher} has ever had. The integration
 * suite runs with {@code flashseats.outbox.transport=log}, so the broker path was previously
 * exercised by nothing.
 */
@DisplayName("The outbox publishes only what the broker durably kept")
class RabbitOutboxPublisherTest {

    private static final String EXCHANGE = "order.events.exchange";
    private static final String ROUTED_QUEUE = "test.order-confirmed.queue";

    private static RabbitMQContainer broker;
    private static CachingConnectionFactory connections;
    private static RabbitTemplate rabbit;

    @BeforeAll
    static void startBroker() {
        broker = new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));
        broker.start();

        connections = new CachingConnectionFactory(broker.getHost(), broker.getAmqpPort());
        connections.setUsername(broker.getAdminUsername());
        connections.setPassword(broker.getAdminPassword());
        // The two settings the production profile sets, and the two this test exists to verify.
        connections.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        connections.setPublisherReturns(true);

        rabbit = new RabbitTemplate(connections);
        rabbit.setMandatory(true);
        // Mirrors OutboxPublisherConfig. The publisher reads CorrelationData.getReturned() and does
        // not need this, but without it RabbitTemplate logs "Returned message but no callback
        // available" — so leaving it out here would make the test's own output contradict what the
        // running application prints.
        rabbit.setReturnsCallback(returned -> {});

        // `order.confirmed` is bound; `order.refunded` deliberately is NOT, which is what gives us
        // an unroutable message to publish without breaking the broker in some artificial way.
        RabbitAdmin admin = new RabbitAdmin(connections);
        TopicExchange exchange = new TopicExchange(EXCHANGE, true, false);
        Queue queue = new Queue(ROUTED_QUEUE, true);
        admin.declareExchange(exchange);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with("order.confirmed"));
    }

    @AfterAll
    static void stopBroker() {
        if (connections != null) {
            connections.destroy();
        }
        if (broker != null) {
            broker.stop();
        }
    }

    private RabbitOutboxPublisher publisher() {
        return new RabbitOutboxPublisher(rabbit, Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("An acknowledged message comes back, so its row may be marked PROCESSED")
    void acknowledgedEventsAreReturned() {
        OutboxEvent event = confirmedEvent();

        List<UUID> published = publisher().publish(List.of(event));

        assertThat(published).containsExactly(event.getId());
    }

    @Test
    @DisplayName("A message the broker acknowledges but routes NOWHERE is not reported as published")
    void unroutableEventsAreNotReturned() {
        // ORDER_REFUNDED routes to `order.refunded`, which no queue is bound to. The broker accepts
        // the message and has nothing to do with it.
        //
        // This is the case publisher confirms ALONE would get wrong: the confirm is an ack, and
        // without `mandatory` the message would be discarded silently while the outbox row was
        // marked PROCESSED. `notification.order-refunded.queue` having no consumer today is not a
        // hypothetical version of this — it is this, one binding away.
        OutboxEvent event = new OutboxEvent("Order", "TK-00002", "ORDER_REFUNDED", "{\"a\":2}");

        List<UUID> published = publisher().publish(List.of(event));

        assertThat(published).isEmpty();
    }

    @Test
    @DisplayName("A mixed batch reports only the routed events, so the rest are retried")
    void partialSuccessIsAReturnValueNotAnException() {
        OutboxEvent routed = confirmedEvent();
        OutboxEvent unroutable = new OutboxEvent("Order", "TK-00004", "ORDER_REFUNDED", "{\"a\":4}");

        // Partial success is the normal case, not an error: whatever is missing stays PROCESSING and
        // the stale-claim sweep returns it to PENDING.
        List<UUID> published = publisher().publish(List.of(routed, unroutable));

        assertThat(published).containsExactly(routed.getId());
    }

    @Test
    @DisplayName("An empty batch does nothing and waits for nothing")
    void emptyBatchIsNotAnError() {
        assertThat(publisher().publish(List.of())).isEmpty();
    }

    private OutboxEvent confirmedEvent() {
        return new OutboxEvent(
                "Order", "TK-0000" + UUID.randomUUID(), "ORDER_CONFIRMED", "{\"orderNumber\":\"x\"}");
    }
}
