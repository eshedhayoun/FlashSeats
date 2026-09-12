package com.flashseats.notification.consumer;

import com.flashseats.notification.config.RabbitTopologyConfig;
import com.flashseats.notification.dto.OrderConfirmedPayload;
import com.flashseats.notification.model.NotificationKind;
import com.flashseats.notification.service.EmailComposer;
import com.flashseats.notification.service.EmailDispatcher;
import com.flashseats.notification.service.NotificationLogService;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Tells a buyer their money is coming back.
 *
 * <p>Reached when a charge settled but the order could not be committed — the seats were claimed by
 * a concurrent expiry between the charge and the commit — so {@code order} refunds and records
 * {@code REFUNDED} (ADR-012). The buyer has a card statement showing a charge and nothing else; this
 * is the message that stops them learning about it from their bank.
 *
 * <p><strong>Why this class had to exist.</strong> {@code order} has written {@code ORDER_REFUNDED}
 * outbox rows since Phase 1 and {@code notification.order-refunded.queue} has been declared and bound
 * for just as long, with <strong>no consumer</strong>. Every refund therefore queued a message that
 * was delivered to a durable queue and never read: unbounded growth on the broker, and a buyer who
 * was refunded in silence. Deleting the writes was the cheaper fix and the wrong one — taking
 * someone's money and saying nothing is not a state this system should be able to reach.
 *
 * <p>The shape is {@link OrderConfirmedConsumer}'s, minus the PDF: claim, send, record, acknowledge,
 * with no transaction open across the send (ADR-023). The claim is
 * {@code UNIQUE(order_number, kind)}, and {@code kind} is what lets one order carry both a
 * {@code TICKET_DELIVERY} and a {@code REFUND_NOTICE} without either suppressing the other.
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "flashseats.notification.enabled", havingValue = "true", matchIfMissing = true)
public class OrderRefundedConsumer {

    private static final NotificationKind KIND = NotificationKind.REFUND_NOTICE;

    private final NotificationLogService logs;
    private final EmailComposer composer;
    private final EmailDispatcher dispatcher;
    private final ObjectMapper json;

    public OrderRefundedConsumer(
            NotificationLogService logs,
            EmailComposer composer,
            EmailDispatcher dispatcher,
            ObjectMapper json) {
        this.logs = logs;
        this.composer = composer;
        this.dispatcher = dispatcher;
        this.json = json;
    }

    @RabbitListener(queues = RabbitTopologyConfig.QUEUE_ORDER_REFUNDED)
    public void onOrderRefunded(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String orderNumber = null;
        boolean delivered = false;

        try {
            // The refund payload carries a null `event` and no items — there were no seats to
            // describe. `refundBodyFor` is written not to touch either, and that is a contract
            // between these two classes, not an accident of the current template.
            OrderConfirmedPayload payload =
                    json.readValue(message.getBody(), OrderConfirmedPayload.class);
            orderNumber = payload.orderNumber();

            if (!logs.claim(orderNumber, KIND, payload.userEmail())) {
                channel.basicAck(deliveryTag, false);
                return;
            }

            dispatcher.send(
                    payload.userEmail(),
                    composer.refundSubjectFor(payload),
                    composer.refundBodyFor(payload));
            delivered = true;

            logs.markSent(orderNumber, KIND);
            channel.basicAck(deliveryTag, false);
            log.info("Sent refund notice for {} to {}", orderNumber, payload.userEmail());

        } catch (Exception failure) {
            log.error("Could not send the refund notice for {}", orderNumber, failure);
            if (orderNumber != null) {
                recordFailure(orderNumber, delivered, failure);
            }
            channel.basicNack(deliveryTag, false, false);
        }
    }

    /**
     * Same rule as the ticket path, for the same reason: <strong>{@code DLQ} means the work did not
     * happen</strong> (ADR-042). A dead-lettered row is re-claimable so an operator's replay actually
     * sends, so marking a row {@code DLQ} after the mail server accepted the message would authorise
     * a second notice. A duplicate refund email is less alarming than a duplicate ticket and it is
     * still a message saying "we refunded you" arriving twice for one refund.
     */
    private void recordFailure(String orderNumber, boolean delivered, Exception failure) {
        if (delivered) {
            log.warn(
                    "Refund notice for {} was delivered but the outcome could not be recorded "
                            + "cleanly; marking SENT so a replay cannot send it twice",
                    orderNumber,
                    failure);
            logs.markSent(orderNumber, KIND);
            return;
        }
        logs.markDeadLettered(orderNumber, KIND, failure.toString());
    }
}
