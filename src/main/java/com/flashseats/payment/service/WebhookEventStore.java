package com.flashseats.payment.service;

import com.flashseats.payment.repository.WebhookEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three <strong>short</strong> transactions around one webhook delivery: claim it, mark it done,
 * or give it back.
 *
 * <p>On its own bean for the same reason as {@link PaymentTransactionStore}: Spring's transaction
 * proxy does not intercept self-invocation, so a {@code @Transactional} method called from another
 * method on the same object runs with no transaction at all, silently. For a claim that is not a
 * style problem — the claim has to be <em>committed</em> before the settlement it guards begins, or
 * the other two replicas cannot see it and all three settle the same charge.
 *
 * <p>{@code REQUIRES_NEW} so each is independent of whatever the settlement does; in particular the
 * release must commit even though the settlement's transaction rolled back.
 */
@Component
public class WebhookEventStore {

    private final WebhookEventRepository webhookEvents;

    public WebhookEventStore(WebhookEventRepository webhookEvents) {
        this.webhookEvents = webhookEvents;
    }

    /** @return true if this caller may settle; false if the delivery was already claimed */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(String eventId, String eventType, String paymentIntentId, String holdToken) {
        return webhookEvents.claimIfAbsent(eventId, eventType, paymentIntentId, holdToken) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessed(String eventId) {
        webhookEvents.markProcessed(eventId);
    }

    /** Gives the claim back so the provider's redelivery can retry (ADR-038). */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void release(String eventId) {
        webhookEvents.release(eventId);
    }
}
