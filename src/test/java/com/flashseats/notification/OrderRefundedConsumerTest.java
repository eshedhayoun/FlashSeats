package com.flashseats.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashseats.notification.consumer.OrderRefundedConsumer;
import com.flashseats.notification.dto.OrderConfirmedPayload;
import com.flashseats.notification.model.NotificationKind;
import com.flashseats.notification.service.EmailComposer;
import com.flashseats.notification.service.EmailDispatcher;
import com.flashseats.notification.service.NotificationLogService;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.ObjectMapper;

class OrderRefundedConsumerTest {

    private final NotificationLogService logs =
            org.mockito.Mockito.mock(NotificationLogService.class);

    private final EmailComposer composer =
            org.mockito.Mockito.mock(EmailComposer.class);

    private final EmailDispatcher dispatcher =
            org.mockito.Mockito.mock(EmailDispatcher.class);

    private final ObjectMapper json =
            org.mockito.Mockito.mock(ObjectMapper.class);

    private final Channel channel =
            org.mockito.Mockito.mock(Channel.class);

    private final OrderRefundedConsumer consumer =
            new OrderRefundedConsumer(
                    logs,
                    composer,
                    dispatcher,
                    json);

    @Test
    void refundNoticeIsSentToTheBuyer() throws Exception {

        OrderConfirmedPayload payload =
                new OrderConfirmedPayload(
                        "ORDER_REFUNDED",
                        "TK-12345",
                        "receipt-token",
                        "buyer@example.com",
                        15_000L,
                        "USD",
                        Instant.now(),
                        null,
                        List.of());

        MessageProperties properties =
                new MessageProperties();

        properties.setDeliveryTag(42L);

        Message message =
                new Message(
                        "ignored".getBytes(StandardCharsets.UTF_8),
                        properties);

        when(json.readValue(
                any(byte[].class),
                eq(OrderConfirmedPayload.class)))
                .thenReturn(payload);

        when(logs.claim(
                "TK-12345",
                NotificationKind.REFUND_NOTICE,
                "buyer@example.com"))
                .thenReturn(true);

        when(composer.refundSubjectFor(payload))
                .thenReturn("Refund issued for TK-12345");

        when(composer.refundBodyFor(payload))
                .thenReturn("<html>Refunded</html>");

        consumer.onOrderRefunded(
                message,
                channel);

        verify(dispatcher).send(
                "buyer@example.com",
                "Refund issued for TK-12345",
                "<html>Refunded</html>");

        verify(logs).markSent(
                "TK-12345",
                NotificationKind.REFUND_NOTICE);

        verify(channel).basicAck(
                42L,
                false);

        verify(channel, never()).basicNack(
                any(Long.class),
                eq(false),
                eq(false));
    }
}