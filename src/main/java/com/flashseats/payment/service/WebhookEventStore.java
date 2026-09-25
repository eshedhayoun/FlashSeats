package com.flashseats.payment.service;

import com.flashseats.payment.repository.WebhookEventRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The three <strong>short</strong> transactions around one webhook delivery: claim, mark done, or
 * give back. A separate bean because the claim must be <em>committed</em> before the settlement
 * begins, or all three replicas settle the same charge, and self-invocation would skip the proxy.
 * {@code REQUIRES_NEW}, so a release commits even when the settlement rolled back.
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
