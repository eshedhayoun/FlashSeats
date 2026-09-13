package com.flashseats.payment.facade;

/**
 * The outcome of a charge, as {@code order} sees it.
 *
 * <p>Four fields, all of them read. It carried three more — {@code failureCode}, {@code retryable}
 * and {@code requiresAction} — and no caller ever looked at any of them, while {@code retryable}'s
 * own javadoc called it "the field that matters". It was not: whether a buyer may try another card
 * is decided from their remaining attempt budget, which lives on the order, and a flag here that
 * nobody read made the real rule harder to find rather than easier.
 *
 * <p>{@code failureReason} is the provider's wording and reaches the buyer through
 * {@code PaymentDeclinedException}. A decline is reported by returning, not by throwing: a refused
 * card is a <em>correct answer</em> the caller must act on — keep the hold, let them retry — not an
 * exceptional condition. Only genuine faults are thrown.
 */
public record PaymentResult(
        String transactionReference,
        boolean succeeded,
        String gatewayReference,
        String failureReason) {}
