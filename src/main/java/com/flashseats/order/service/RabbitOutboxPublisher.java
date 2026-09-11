package com.flashseats.order.service;

import com.flashseats.order.model.OutboxEvent;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/**
 * Publishes outbox rows to the broker, and reports only what the broker <strong>kept</strong>.
 *
 * <p>The exchange and routing keys are repeated here rather than imported from {@code notification},
 * on purpose. The two modules are coupled by the <strong>wire format</strong>, not by a Java type:
 * importing a constant would create a compile-time dependency across an asynchronous boundary and
 * make one module's redeploy the other's problem. Two short strings are the honest price of that
 * independence.
 *
 * <p>Called from {@link OutboxRelay} with no transaction open. Messages are persistent, so an order
 * confirmed while the broker restarts still has its ticket queued when it returns.
 *
 * <h2>Why this waits, and what it waits for</h2>
 *
 * <p>{@code RabbitTemplate.send} is fire-and-forget: it returns once the frame is written to the
 * socket. The relay used to mark a row {@code PROCESSED} on that basis, which means a broker that
 * accepted the bytes and died before persisting them lost the message <em>with the outbox row
 * already burned</em> — precisely the failure an outbox exists to prevent.
 *
 * <p>So every message carries a {@link CorrelationData} and this class waits for the publisher
 * confirm. <strong>Two independent things can go wrong and only one of them is an ack</strong>:
 *
 * <ul>
 *   <li>A <strong>nack</strong> means the broker refused it.
 *   <li>A <strong>return</strong> means the broker accepted it and had nowhere to put it. A confirm
 *       says "I have this message", not "a queue has this message" — an exchange with no matching
 *       binding acks happily and discards. That is not hypothetical here: the whole topology lives
 *       behind {@code flashseats.notification.enabled}, so a deployment with notification switched
 *       off everywhere has an exchange bound to nothing, and every ticket would be confirmed into
 *       the void. {@code mandatory} plus publisher-returns is what turns that into a failure.
 * </ul>
 *
 * <p>Both are treated the same way: the id is left out of the result, the row stays
 * {@code PROCESSING}, and the stale-claim sweep retries it.
 */
@Slf4j
public class RabbitOutboxPublisher implements OutboxPublisher {

    /** Contract with {@code notification}. Changing either string is a breaking change. */
    private static final String EXCHANGE = "order.events.exchange";

    private static final String ROUTING_CONFIRMED = "order.confirmed";
    private static final String ROUTING_REFUNDED = "order.refunded";

    private final RabbitTemplate rabbit;
    private final Duration confirmTimeout;

    public RabbitOutboxPublisher(RabbitTemplate rabbit, Duration confirmTimeout) {
        this.rabbit = rabbit;
        this.confirmTimeout = confirmTimeout;
    }

    @Override
    public List<UUID> publish(List<OutboxEvent> events) {
        // Send everything first, then wait once. Waiting inside the send loop would serialise the
        // batch behind the slowest confirm and turn a broker hiccup into batchSize x timeout.
        Map<UUID, CorrelationData> pending = new LinkedHashMap<>(events.size());
        for (OutboxEvent event : events) {
            CorrelationData correlation = new CorrelationData(event.getId().toString());
            try {
                rabbit.send(EXCHANGE, routingKeyFor(event.getEventType()), toMessage(event), correlation);
                pending.put(event.getId(), correlation);
            } catch (RuntimeException notSent) {
                // Never reached the socket. Nothing to wait for.
                log.error("Could not send outbox event {}", event.getId(), notSent);
            }
        }

        // One deadline for the whole batch, not one per message.
        long deadline = System.nanoTime() + confirmTimeout.toNanos();
        List<UUID> confirmed = new ArrayList<>(pending.size());

        for (Map.Entry<UUID, CorrelationData> entry : pending.entrySet()) {
            if (isDurablyAccepted(entry.getKey(), entry.getValue(), deadline)) {
                confirmed.add(entry.getKey());
            }
        }
        return confirmed;
    }

    private boolean isDurablyAccepted(UUID id, CorrelationData correlation, long deadline) {
        try {
            long remaining = Math.max(0, deadline - System.nanoTime());
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(remaining, TimeUnit.NANOSECONDS);

            if (confirm == null || !confirm.isAck()) {
                log.error(
                        "Broker refused outbox event {}: {}",
                        id,
                        confirm == null ? "no confirm" : confirm.getReason());
                return false;
            }

            // Checked AFTER the confirm resolves, which is the only correct order: Spring populates
            // the returned message before completing the future, so a return can never be missed by
            // looking here, and looking earlier would race it.
            if (correlation.getReturned() != null) {
                log.error(
                        "Outbox event {} was acknowledged but routed nowhere ({}). Is the"
                                + " notification topology declared on any replica?",
                        id,
                        correlation.getReturned().getReplyText());
                return false;
            }
            return true;

        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted awaiting confirmation of outbox event {}", id);
            return false;
        } catch (Exception unconfirmed) {
            // A timeout included. The message may well have landed — which is exactly why the row is
            // left PROCESSING rather than PROCESSED or PENDING: it will be retried, and the
            // consumer's UNIQUE(order_number, kind) absorbs the duplicate. At-least-once is the
            // guarantee, and this is the half that earns it.
            log.error("No confirmation for outbox event {} within {}", id, confirmTimeout, unconfirmed);
            return false;
        }
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
