package com.flashseats.order.service;

import com.flashseats.order.exception.NotificationPayloadUnavailableException;
import com.flashseats.order.model.OutboxEvent;
import com.flashseats.order.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-queues a fulfilment message for an order whose ticket never arrived.
 *
 * <h2>Why this lives in {@code order} and not in {@code notification}</h2>
 *
 * <p>A resend needs the original <strong>payload</strong> — the event's title and venue, every line
 * item, the receipt token — and {@code notification_logs} does not carry it. It records that a
 * delivery was attempted and how it failed, never what was in it. The durable copy is
 * {@code outbox_events.payload}, and {@code order} owns that table.
 *
 * <p>So the endpoint sits where the state is (ADR-043), exactly as {@code rebuild-stock} does, and
 * the path still names what the operator is thinking about. The alternative designs were both
 * worse: draining the AMQP dead-letter queue to find one message means re-enqueueing everything that
 * does not match, and having {@code notification} ask {@code order} for the payload would add a
 * facade edge across an asynchronous boundary that exists precisely so the two can be deployed
 * independently.
 *
 * <h2>Why it is only three lines of work</h2>
 *
 * <p>Writing a new outbox row is the <em>whole</em> mechanism. The relay publishes it like any
 * other, and on the far side {@code NotificationLogService.claim} already falls through from
 * {@code claimIfAbsent} to {@code reclaimDeadLettered} — the conditional {@code UPDATE ... WHERE
 * status = 'DLQ'} ADR-038 added so that a replay would actually send. Nothing new is needed on
 * either side; the capability was built and had no trigger.
 *
 * <p><strong>Resending something that already worked is safe by construction</strong>, which is the
 * property that makes this endpoint usable under pressure. A {@code SENT} row is not {@code DLQ}, so
 * the re-claim matches nothing, the claim returns false, and the consumer acknowledges without
 * sending. An operator who cannot tell whether the first attempt landed can press this without
 * risking a second ticket.
 */
@Slf4j
@Service
public class NotificationResendService {

    private final OutboxEventRepository outbox;

    public NotificationResendService(OutboxEventRepository outbox) {
        this.outbox = outbox;
    }

    /**
     * Queues the order's confirmation message again.
     *
     * @return the id of the new outbox row
     * @throws NotificationPayloadUnavailableException if the original message has been purged
     */
    @Transactional
    public String resendTicket(String orderNumber) {
        OutboxEvent original = outbox
                .findFirstByAggregateIdAndEventTypeOrderByCreatedAtDesc(
                        orderNumber, OrderCommitService.EVENT_ORDER_CONFIRMED)
                .orElseThrow(() -> new NotificationPayloadUnavailableException(orderNumber));

        // A NEW row, not a reset of the old one. The original is the record that a message was
        // published, and rewriting history to re-drive it would lose the fact that this order needed
        // two attempts — which is the first thing anyone asks when it needs a third.
        OutboxEvent replay = new OutboxEvent(
                original.getAggregateType(),
                original.getAggregateId(),
                original.getEventType(),
                original.getPayload());
        outbox.save(replay);

        log.warn("Operator queued a resend of {} as outbox event {}", orderNumber, replay.getId());
        return replay.getId().toString();
    }
}
