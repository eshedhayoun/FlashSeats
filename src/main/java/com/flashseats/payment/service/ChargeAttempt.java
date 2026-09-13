package com.flashseats.payment.service;

/**
 * One charge attempt, opened.
 *
 * <p>{@code resumableGatewayReference} is non-null only when this hold already has an intent
 * awaiting authentication — the 3-D Secure case, where the correct move is to <em>retrieve</em> that
 * intent rather than start a second one.
 *
 * <p>A record rather than the entity, because {@link PaymentTransactionStore} reads it in its own
 * short transaction and everything after runs detached: handing a detached entity across a network
 * call is how a lazy field becomes a {@code LazyInitializationException} in the middle of taking
 * someone's money.
 */
public record ChargeAttempt(String transactionReference, String resumableGatewayReference) {

    public boolean isResume() {
        return resumableGatewayReference != null;
    }
}
