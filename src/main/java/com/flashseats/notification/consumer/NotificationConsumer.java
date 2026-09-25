package com.flashseats.notification.consumer;

import com.flashseats.notification.config.RabbitTopologyConfig;
import com.flashseats.notification.dto.OrderConfirmedPayload;
import com.flashseats.notification.model.NotificationKind;
import com.flashseats.notification.service.EmailComposer;
import com.flashseats.notification.service.EmailDispatcher;
import com.flashseats.notification.service.NotificationLogService;
import com.flashseats.shared.ticket.TicketDocument;
import com.flashseats.shared.ticket.TicketPdfRenderer;
import com.rabbitmq.client.Channel;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.EnumMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Turns order events into email: a PDF ticket on confirmation, a notice on refund.
 *
 * <p>Both listeners run one delivery flow, fixed by the transaction rules (ADR-023): claim the log
 * row in its own short transaction, send with <strong>no transaction open</strong>, record the
 * outcome, then acknowledge. The claim is the idempotency guard ({@code UNIQUE(order_number, kind)},
 * insert-then-send). A crash between send and ack can resend once on redelivery; at-least-once
 * beats a design that can silently never send.
 *
 * <p>Failures are not retried here. A malformed payload or a render failure fails identically every
 * time, so the message goes straight to the dead-letter queue (ADR-029).
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "flashseats.notification.enabled", havingValue = "true", matchIfMissing = true)
public class NotificationConsumer {

    /** The one step that differs between the two kinds of mail. */
    @FunctionalInterface
    private interface Send {
        void to(OrderConfirmedPayload payload) throws Exception;
    }

    private final NotificationLogService logs;
    private final TicketPdfRenderer pdf;
    private final EmailComposer composer;
    private final EmailDispatcher dispatcher;
    private final ObjectMapper json;
    private final Map<NotificationKind, Counter> delivered = new EnumMap<>(NotificationKind.class);
    private final Map<NotificationKind, Counter> failed = new EnumMap<>(NotificationKind.class);

    public NotificationConsumer(
            NotificationLogService logs,
            TicketPdfRenderer pdf,
            EmailComposer composer,
            EmailDispatcher dispatcher,
            ObjectMapper json,
            MeterRegistry meters) {
        this.logs = logs;
        this.pdf = pdf;
        this.composer = composer;
        this.dispatcher = dispatcher;
        this.json = json;
        for (NotificationKind kind : NotificationKind.values()) {
            delivered.put(kind, Counter.builder("flashseats.notification.delivered")
                    .tag("kind", kind.name())
                    .description("Notifications successfully sent")
                    .register(meters));
            failed.put(kind, Counter.builder("flashseats.notification.failed")
                    .tag("kind", kind.name())
                    .description("Notification delivery failures")
                    .register(meters));
        }
    }

    @RabbitListener(queues = RabbitTopologyConfig.QUEUE_ORDER_CONFIRMED)
    public void onOrderConfirmed(Message message, Channel channel) throws IOException {
        deliver(message, channel, NotificationKind.TICKET_DELIVERY, payload -> dispatcher.send(
                payload.userEmail(),
                composer.subjectFor(payload),
                composer.bodyFor(payload),
                pdf.render(toDocument(payload)),
                payload.orderNumber() + ".pdf"));
    }

    /** The refund payload has a null {@code event} and no items; the refund template reads neither. */
    @RabbitListener(queues = RabbitTopologyConfig.QUEUE_ORDER_REFUNDED)
    public void onOrderRefunded(Message message, Channel channel) throws IOException {
        deliver(message, channel, NotificationKind.REFUND_NOTICE, payload -> dispatcher.send(
                payload.userEmail(),
                composer.refundSubjectFor(payload),
                composer.refundBodyFor(payload)));
    }

    private void deliver(Message message, Channel channel, NotificationKind kind, Send send)
            throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        String orderNumber = null;
        boolean sent = false;

        try {
            OrderConfirmedPayload payload =
                    json.readValue(message.getBody(), OrderConfirmedPayload.class);
            orderNumber = payload.orderNumber();

            if (!logs.claim(orderNumber, kind, payload.userEmail())) {
                channel.basicAck(deliveryTag, false); // someone already sent it, or is sending it
                return;
            }

            send.to(payload);
            sent = true;

            logs.markSent(orderNumber, kind);
            delivered.get(kind).increment();
            channel.basicAck(deliveryTag, false);
            log.info("Sent {} for {} to {}", kind, orderNumber, payload.userEmail());

        } catch (Exception failure) {
            log.error("Could not deliver {} for {}", kind, orderNumber, failure);
            failed.get(kind).increment();
            if (orderNumber != null) {
                recordFailure(orderNumber, kind, sent, failure);
            }
            channel.basicNack(deliveryTag, false, false); // requeue=false: to the dead-letter queue
        }
    }

    /**
     * Never marks {@code DLQ} once the mail server has accepted the message (ADR-042). A
     * {@code DLQ} row is re-claimable so an operator's replay sends (ADR-038); marking a delivered
     * message {@code DLQ} would authorise a second ticket. The redelivery then finds the row
     * {@code SENT}, wins no claim, and is acknowledged quietly.
     */
    private void recordFailure(String orderNumber, NotificationKind kind, boolean sent, Exception failure) {
        if (sent) {
            log.warn("{} for {} was sent but not recorded cleanly; marking SENT so a replay cannot"
                    + " send it twice", kind, orderNumber, failure);
            logs.markSent(orderNumber, kind);
            return;
        }
        logs.markDeadLettered(orderNumber, kind, failure.toString());
    }

    /**
     * Maps the wire payload onto the renderer's narrower input (ADR-050). The renderer cannot see
     * the {@code receiptToken} or the amount, so it cannot print a bearer capability onto a ticket.
     */
    private static TicketDocument toDocument(OrderConfirmedPayload payload) {
        return new TicketDocument(
                payload.orderNumber(),
                payload.event().title(),
                payload.event().venueName(),
                payload.event().startTime(),
                payload.items().stream()
                        .map(item -> new TicketDocument.Seat(item.tierName(), item.quantity()))
                        .toList());
    }
}
