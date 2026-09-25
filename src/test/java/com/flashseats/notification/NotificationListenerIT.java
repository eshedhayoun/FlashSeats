package com.flashseats.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import com.flashseats.notification.config.RabbitTopologyConfig;
import com.flashseats.notification.model.NotificationKind;
import com.flashseats.notification.model.NotificationLog;
import com.flashseats.notification.model.NotificationStatus;
import com.flashseats.notification.repository.NotificationLogRepository;
import com.flashseats.notification.service.NotificationLogService;
import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Fulfilment, driven through the real broker and the real listeners.
 *
 * <p>The test profile turns {@code flashseats.notification.enabled} off, which removes the topology,
 * both consumers and {@code EmailDispatcher} — so until this class, every guarantee below was
 * asserted in prose and exercised by nothing. {@code CLAUDE.md} names that as a trap: disabling a
 * feature in the test profile leaves the configuration production runs with no coverage. The seam is
 * this class turning it back on, against its own broker.
 *
 * <p>What is real: RabbitMQ, the production topology from {@link RabbitTopologyConfig} (dead-letter
 * exchange included), both {@code @RabbitListener}s with manual acknowledgement, PostgreSQL and the
 * {@code UNIQUE(order_number, kind)} claim, and the PDF render. What is not: SMTP, replaced by a
 * recording {@link JavaMailSenderImpl} that can be told to fail. {@code outbox.transport} stays
 * {@code log} — messages are published straight onto {@code order.events.exchange}, because the
 * routing key and the JSON are the contract between {@code order} and this module, and that contract
 * is what is under test.
 *
 * <p>{@code @DirtiesContext}: this context owns a broker and live listeners, so it is closed when the
 * class ends rather than cached with its schedulers running under every later test class.
 */
@DisplayName("Fulfilment through the real broker: one ticket, never two, never none")
@TestPropertySource(properties = "flashseats.notification.enabled=true")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Import(NotificationListenerIT.Broker.class)
class NotificationListenerIT extends IntegrationTest {

    /** Private in {@link RabbitTopologyConfig}; named here because the test reads the queue itself. */
    private static final String DEAD_LETTER_QUEUE = "notification.dead-letter.queue";

    private static final String EMAIL = "buyer@example.com";
    private static final Duration SETTLE = Duration.ofMillis(750);

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private RabbitAdmin admin;

    @Autowired
    private RecordingMailSender mail;

    @Autowired
    private NotificationLogRepository rows;

    @Autowired
    private SaleFixture fixture;

    @MockitoSpyBean
    private NotificationLogService logs;

    @BeforeEach
    void reset() {
        fixture.reset();
        admin.purgeQueue(RabbitTopologyConfig.QUEUE_ORDER_CONFIRMED, false);
        admin.purgeQueue(RabbitTopologyConfig.QUEUE_ORDER_REFUNDED, false);
        admin.purgeQueue(DEAD_LETTER_QUEUE, false);
        mail.reset();
    }

    @Test
    @DisplayName("A confirmed order is mailed once, with its PDF, and the row is SENT")
    void confirmedOrderIsDelivered() {
        publishConfirmed("TK-10001");

        await().atMost(Duration.ofSeconds(10))
                .until(() -> status("TK-10001", ticket()).orElse(null) == NotificationStatus.SENT);

        assertThat(mail.delivered()).singleElement().satisfies(sent -> {
            assertThat(sent.recipient()).isEqualTo(EMAIL);
            assertThat(sent.hasAttachment()).isTrue();
        });
        assertThat(deadLetters()).isZero();
    }

    @Test
    @DisplayName("A redelivered message sends nothing: the claim is the dedupe")
    void duplicateMessageIsDeliveredOnce() {
        // What a crash between send and ack produces, and what a DLQ replay of a SENT row produces.
        // UNIQUE(order_number, kind) is the guard; a SELECT-based check would let both through.
        publishConfirmed("TK-10002");
        publishConfirmed("TK-10002");

        await().atMost(Duration.ofSeconds(10)).until(() -> mail.attempts() >= 1);
        await().during(SETTLE).atMost(Duration.ofSeconds(5)).until(() -> mail.delivered().size() == 1);

        assertThat(status("TK-10002", ticket())).contains(NotificationStatus.SENT);
        assertThat(deadLetters()).isZero();
    }

    @Test
    @DisplayName("A malformed message goes straight to the DLQ, once, with no retry (ADR-029)")
    void malformedMessageIsDeadLetteredOnce() {
        rabbit.send(
                RabbitTopologyConfig.ORDER_EXCHANGE,
                RabbitTopologyConfig.ROUTING_ORDER_CONFIRMED,
                new Message("{not json".getBytes(StandardCharsets.UTF_8), new MessageProperties()));

        // It fails identically every time, so it must not be requeued: a requeue loop would pin the
        // consumer on one poison message and delay every ticket behind it.
        await().atMost(Duration.ofSeconds(10)).until(() -> deadLetters() == 1);
        await().during(SETTLE).atMost(Duration.ofSeconds(5)).until(() -> deadLetters() == 1);

        assertThat(mail.attempts()).isZero();
        assertThat(rows.count()).isZero();
    }

    @Test
    @DisplayName("A send that fails is dead-lettered after ONE attempt, and the row says DLQ (ADR-029)")
    void failedSendIsDeadLetteredAfterOneAttempt() {
        mail.failNext(1);

        publishConfirmed("TK-10003");

        await().atMost(Duration.ofSeconds(10)).until(() -> deadLetters() == 1);
        await().during(SETTLE).atMost(Duration.ofSeconds(5)).until(() -> mail.attempts() == 1);

        NotificationLog row = row("TK-10003", ticket());
        assertThat(row.getStatus()).isEqualTo(NotificationStatus.DLQ);
        assertThat(row.getRetryCount()).isEqualTo(1);
        assertThat(row.getFailureReason()).contains("smtp down");
        assertThat(mail.delivered()).isEmpty();
    }

    @Test
    @DisplayName("Replaying a dead letter after the outage sends the ticket exactly once (ADR-038)")
    void deadLetterReplaySends() {
        mail.failNext(1);
        publishConfirmed("TK-10004");
        await().atMost(Duration.ofSeconds(10)).until(() -> deadLetters() == 1);

        // The operator's replay: take it off the DLQ and put it back on the exchange. The DLQ row is
        // re-claimable precisely so this sends; a permanent claim would acknowledge and drop it.
        replayDeadLetter();

        await().atMost(Duration.ofSeconds(10))
                .until(() -> status("TK-10004", ticket()).orElse(null) == NotificationStatus.SENT);
        assertThat(mail.delivered()).hasSize(1);
        assertThat(mail.attempts()).isEqualTo(2);
        assertThat(deadLetters()).isZero();
    }

    @Test
    @DisplayName("Mail accepted, then the bookkeeping fails: the row is SENT and a replay sends nothing (ADR-042)")
    void deliveredButUnrecordedIsNeverDeadLettered() {
        // SMTP has accepted the message; recording that fails. The consumer nacks — so the message
        // does reach the DLQ — but the ROW must say SENT, because DLQ is re-claimable by design and a
        // DLQ row here would authorise the replay to mail the buyer a second ticket.
        doThrow(new IllegalStateException("connection reset during markSent"))
                .doCallRealMethod()
                .when(logs)
                .markSent(eq("TK-10005"), eq(ticket()));

        publishConfirmed("TK-10005");

        await().atMost(Duration.ofSeconds(10)).until(() -> deadLetters() == 1);
        assertThat(status("TK-10005", ticket())).contains(NotificationStatus.SENT);
        assertThat(mail.delivered()).hasSize(1);

        replayDeadLetter();

        await().during(SETTLE).atMost(Duration.ofSeconds(5)).until(() -> deadLetters() == 0);
        assertThat(mail.delivered())
                .as("a replay of a delivered ticket must not send a second one")
                .hasSize(1);
        assertThat(status("TK-10005", ticket())).contains(NotificationStatus.SENT);
    }

    @Test
    @DisplayName("A refund is mailed once, without an attachment, on its own routing key")
    void refundNoticeIsDelivered() {
        rabbit.send(
                RabbitTopologyConfig.ORDER_EXCHANGE,
                RabbitTopologyConfig.ROUTING_ORDER_REFUNDED,
                json("""
                        {"eventType":"ORDER_REFUNDED","orderNumber":"TK-10006","receiptToken":"r",
                         "userEmail":"%s","totalAmountCents":15000,"currency":"USD",
                         "confirmedAt":"2026-09-25T10:00:00Z","event":null,"items":[]}
                        """.formatted(EMAIL)));

        await().atMost(Duration.ofSeconds(10)).until(() ->
                status("TK-10006", NotificationKind.REFUND_NOTICE).orElse(null) == NotificationStatus.SENT);

        assertThat(mail.delivered()).singleElement().satisfies(sent ->
                assertThat(sent.hasAttachment()).isFalse());
        // Its own kind: a refunded order may also have been sent a ticket, and both are owed.
        assertThat(status("TK-10006", ticket())).isEmpty();
    }

    // ----------------------------------------------------------------- helpers

    private void publishConfirmed(String orderNumber) {
        rabbit.send(
                RabbitTopologyConfig.ORDER_EXCHANGE,
                RabbitTopologyConfig.ROUTING_ORDER_CONFIRMED,
                json("""
                        {"eventType":"ORDER_CONFIRMED","orderNumber":"%s","receiptToken":"r",
                         "userEmail":"%s","totalAmountCents":15000,"currency":"USD",
                         "confirmedAt":"2026-09-25T10:00:00Z",
                         "event":{"eventId":1,"title":"Listener Night","venueName":"Hall A",
                                  "startTime":"2026-10-01T20:00:00Z"},
                         "items":[{"tierId":1,"tierName":"GA","quantity":2,"unitPriceCents":7500}]}
                        """.formatted(orderNumber, EMAIL)));
    }

    private void replayDeadLetter() {
        Message dead = rabbit.receive(DEAD_LETTER_QUEUE, 5_000);
        assertThat(dead).as("a dead letter to replay").isNotNull();
        rabbit.send(
                RabbitTopologyConfig.ORDER_EXCHANGE,
                RabbitTopologyConfig.ROUTING_ORDER_CONFIRMED,
                new Message(dead.getBody(), new MessageProperties()));
    }

    private static Message json(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    private long deadLetters() {
        var info = admin.getQueueInfo(DEAD_LETTER_QUEUE);
        return info == null ? -1 : info.getMessageCount();
    }

    private Optional<NotificationStatus> status(String orderNumber, NotificationKind kind) {
        return rows.findByOrderNumberAndKind(orderNumber, kind).map(NotificationLog::getStatus);
    }

    private NotificationLog row(String orderNumber, NotificationKind kind) {
        return rows.findByOrderNumberAndKind(orderNumber, kind).orElseThrow();
    }

    private static NotificationKind ticket() {
        return NotificationKind.TICKET_DELIVERY;
    }

    // ----------------------------------------------------------------- fixtures

    @TestConfiguration(proxyBeanMethods = false)
    static class Broker {

        @Bean
        @ServiceConnection
        RabbitMQContainer rabbitMq() {
            return new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));
        }

        /** Declared so the test can purge and inspect queues; also what declares the topology. */
        @Bean
        RabbitAdmin rabbitAdmin(org.springframework.amqp.rabbit.connection.ConnectionFactory connections) {
            return new RabbitAdmin(connections);
        }

        @Bean
        RecordingMailSender recordingMailSender() {
            return new RecordingMailSender();
        }
    }

    /**
     * SMTP, replaced. Overrides {@code doSend}, the one path every {@code send} overload reaches, so
     * {@code EmailDispatcher} runs unmodified — MIME assembly, attachment and all.
     */
    static class RecordingMailSender extends JavaMailSenderImpl {

        record Sent(String recipient, String subject, boolean hasAttachment) {}

        private final List<Sent> delivered = new CopyOnWriteArrayList<>();
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger failuresRemaining = new AtomicInteger();

        @Override
        protected void doSend(MimeMessage[] messages, Object[] originals) {
            for (MimeMessage message : messages) {
                attempts.incrementAndGet();
                if (failuresRemaining.getAndUpdate(n -> Math.max(0, n - 1)) > 0) {
                    throw new MailSendException("smtp down");
                }
                try {
                    delivered.add(new Sent(
                            message.getAllRecipients()[0].toString(),
                            message.getSubject(),
                            message.getContent() instanceof Multipart));
                } catch (Exception unreadable) {
                    throw new IllegalStateException(unreadable);
                }
            }
        }

        void failNext(int count) {
            failuresRemaining.set(count);
        }

        List<Sent> delivered() {
            return delivered;
        }

        int attempts() {
            return attempts.get();
        }

        void reset() {
            delivered.clear();
            attempts.set(0);
            failuresRemaining.set(0);
        }
    }
}
