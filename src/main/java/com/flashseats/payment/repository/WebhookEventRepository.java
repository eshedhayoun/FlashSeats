package com.flashseats.payment.repository;

import com.flashseats.payment.model.WebhookEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WebhookEventRepository extends JpaRepository<WebhookEvent, String> {

    /**
     * <strong>The delivery claim</strong>, as a rowcount from {@code ON CONFLICT DO NOTHING}. A caught
     * constraint violation would mark the transaction rollback-only, so the "already claimed" return
     * would throw at commit and ask the provider for the replay it just refused (ADR-038).
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
     * <strong>Releases a claim whose work did not happen</strong> (ADR-038, ADR-053), so the provider's
     * redelivery is settled rather than dismissed as a duplicate.
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value = "DELETE FROM webhook_events WHERE stripe_event_id = :eventId AND processed_at IS NULL",
            nativeQuery = true)
    int release(@Param("eventId") String eventId);
}
