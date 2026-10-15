package com.flashseats.order.service;

import com.flashseats.order.exception.OrderErrors;
import com.flashseats.order.model.OutboxEvent;
import com.flashseats.order.repository.OutboxEventRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Re-queues the fulfilment message for an order whose ticket never arrived.
 *
 * <p>It lives in {@code order} because the payload does: {@code notification_logs} records attempts,
 * while the durable copy is {@code outbox_events.payload} (ADR-043). Writing a new outbox row is the
 * whole mechanism: the relay publishes it, and {@code NotificationLogService.claim} re-claims a
 * {@code DLQ} row (ADR-038). Resending a delivered ticket is safe by construction, because a
 * {@code SENT} row is never re-claimed, so the consumer acks without sending.
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
     * @throws com.flashseats.shared.error.FlashSeatsException
     *     {@code NOTIFICATION_PAYLOAD_UNAVAILABLE} if the original message has been purged — see
     *     {@link OrderErrors}
     */
    @Transactional
    public String resendTicket(String orderNumber) {
        OutboxEvent original = outbox
                .findFirstByAggregateIdAndEventTypeOrderByCreatedAtDesc(
                        orderNumber, OrderCommitService.EVENT_ORDER_CONFIRMED)
                .orElseThrow(() -> OrderErrors.notificationPayloadUnavailable(orderNumber));

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
