package com.flashseats.notification.consumer;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import tools.jackson.databind.ObjectMapper;
import com.flashseats.notification.config.RabbitTopologyConfig;
import com.flashseats.notification.dto.OrderConfirmedPayload;
import com.flashseats.notification.model.NotificationKind;
import com.flashseats.notification.service.EmailComposer;
import com.flashseats.notification.service.EmailDispatcher;
import com.flashseats.notification.service.NotificationLogService;
import com.flashseats.notification.service.TicketPdfRenderer;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

/**
 * Turns a confirmed order into a PDF ticket in a buyer's inbox.
 *
 * <p>The shape is fixed by the transaction rules (ADR-023):
 *
 * <ol>
 *   <li>claim the log row — one short transaction, committed
 *   <li>render and send — <strong>no transaction open</strong>
 *   <li>record the outcome — one short transaction
 *   <li>acknowledge
 * </ol>
 *
 * <p>A crash between sending and acknowledging can resend once on redelivery. That is the accepted
 * trade: at-least-once delivery of an email beats a design that can silently never send it.
 *
 * <p>Failures are <strong>not</strong> retried here. A malformed payload or a render failure fails
 * identically on every attempt, so retrying burns 2.5 minutes, delays every other message, produces
 * three identical stack traces and reaches the same dead-letter queue anyway (ADR-029). Transport
 * failures are the broker's concern, not this method's.
 */
@Component
@ConditionalOnProperty(
        name = "flashseats.notification.enabled", havingValue = "true", matchIfMissing = true)
public class OrderConfirmedConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedConsumer.class);
    private static final NotificationKind KIND = NotificationKind.TICKET_DELIVERY;

    private final NotificationLogService logs;
    private final TicketPdfRenderer pdf;
    private final EmailComposer composer;
    private final EmailDispatcher dispatcher;
    private final ObjectMapper json;

    public OrderConfirmedConsumer(
            NotificationLogService logs,
            TicketPdfRenderer pdf,
            EmailComposer composer,
            EmailDispatcher dispatcher,
            ObjectMapper json) {
        this.logs = logs;
        this.pdf = pdf;
        this.composer = composer;
        this.dispatcher = dispatcher;
        this.json = json;
    }

    @RabbitListener(queues = RabbitTopologyConfig.QUEUE_ORDER_CONFIRMED)
    public void onOrderConfirmed(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String orderNumber = null;

        try {
            OrderConfirmedPayload payload =
                    json.readValue(message.getBody(), OrderConfirmedPayload.class);
            orderNumber = payload.orderNumber();

            // The claim IS the idempotency guard. Losing it means someone else already sent this.
            if (!logs.claim(orderNumber, KIND, payload.userEmail())) {
                channel.basicAck(deliveryTag, false);
                return;
            }

            byte[] ticket = pdf.render(payload);
            dispatcher.send(
                    payload.userEmail(),
                    composer.subjectFor(payload),
                    composer.bodyFor(payload),
                    ticket,
                    payload.orderNumber() + ".pdf");

            logs.markSent(orderNumber, KIND);
            channel.basicAck(deliveryTag, false);
            log.info("Sent tickets for {} to {}", orderNumber, payload.userEmail());

        } catch (Exception failure) {
            log.error("Could not deliver tickets for {}", orderNumber, failure);
            if (orderNumber != null) {
                logs.markDeadLettered(orderNumber, KIND, failure.toString());
            }
            // requeue=false: straight to the dead-letter queue, where an operator can see it.
            channel.basicNack(deliveryTag, false, false);
        }
    }
}
