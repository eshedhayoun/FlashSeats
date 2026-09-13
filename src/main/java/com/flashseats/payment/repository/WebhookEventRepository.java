package com.flashseats.payment.repository;

import com.flashseats.payment.model.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {

    /**
     * <strong>The delivery claim.</strong> Inserts the row that makes a second settlement
     * impossible, and reports whether this caller is the one that inserted it.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than an insert whose exception is caught, in the same
     * shape as {@code NotificationLogRepository.claimIfAbsent}. The constraint is still what
     * guarantees exclusivity — a preceding {@code SELECT} would be a race that three replicas all
     * pass — but the outcome arrives as a rowcount instead of a thrown exception, and that matters
     * for more than style: a flush that violates a constraint marks the transaction rollback-only,
     * so a {@code catch} block's {@code return} cannot actually return. It throws
     * {@code UnexpectedRollbackException} at commit, which the caller would read as a failed
     * delivery — and answer the provider with a non-2xx, asking for the replay it just refused
     * (ADR-038).
     *
     * @return 1 if this caller may settle, 0 if the delivery has already been claimed
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    """
                    INSERT INTO webhook_events (stripe_event_id, event_type, payment_intent_id,
                                                hold_token, received_at)
                    VALUES (:eventId, :eventType, :paymentIntentId, :holdToken, now())
                    ON CONFLICT (stripe_event_id) DO NOTHING
                    """,
            nativeQuery = true)
    int claimIfAbsent(
            @Param("eventId") String eventId,
            @Param("eventType") String eventType,
            @Param("paymentIntentId") String paymentIntentId,
            @Param("holdToken") String holdToken);

    /** Marks the claim satisfied. Only ever called after the settlement transaction committed. */
    @Modifying(flushAutomatically = true)
    @Query(
            value = "UPDATE webhook_events SET processed_at = now() WHERE stripe_event_id = :eventId",
            nativeQuery = true)
    int markProcessed(@Param("eventId") String eventId);

    /**
     * <strong>Releases a claim whose work did not happen</strong> (ADR-038).
     *
     * <p>Without this, a settlement that threw would leave a row saying the delivery was handled.
     * The provider's redelivery would then be dismissed as a duplicate and the buyer's charge would
     * never reach an order — the exact failure the DLQ replay hit in Pass 6, one layer down.
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value = "DELETE FROM webhook_events WHERE stripe_event_id = :eventId AND processed_at IS NULL",
            nativeQuery = true)
    int release(@Param("eventId") String eventId);
}
