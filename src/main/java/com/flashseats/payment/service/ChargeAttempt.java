package com.flashseats.payment.service;

/**
 * One charge attempt, opened.
 *
 * <p>{@code gatewayReference} is non-null in two cases, which {@code settled} tells apart:
 *
 * <ul>
 *   <li><strong>resume</strong> — the hold already has an intent awaiting authentication, the 3-D
 *       Secure case, where the correct move is to <em>retrieve</em> that intent rather than start a
 *       second one (ADR-054);
 *   <li><strong>settled</strong> — the hold already has a charge that succeeded and was not refunded.
 *       The order's commit failed ambiguously after it and the buyer is retrying, and the answer is
 *       that charge, not another (ADR-064).
 * </ul>
 *
 * <p>A record rather than the entity, because {@link PaymentTransactionStore} reads it in its own
 * short transaction and everything after runs detached: handing a detached entity across a network
 * call is how a lazy field becomes a {@code LazyInitializationException} in the middle of taking
 * someone's money.
 */
public record ChargeAttempt(String transactionReference, String gatewayReference, boolean settled) {

    static ChargeAttempt fresh(String transactionReference) {
        return new ChargeAttempt(transactionReference, null, false);
    }

    static ChargeAttempt resume(String transactionReference, String gatewayReference) {
        return new ChargeAttempt(transactionReference, gatewayReference, false);
    }

    static ChargeAttempt settled(String transactionReference, String gatewayReference) {
        return new ChargeAttempt(transactionReference, gatewayReference, true);
    }

    public boolean isResume() {
        return gatewayReference != null && !settled;
    }
}
