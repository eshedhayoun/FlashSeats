package com.flashseats.payment.facade;

/**
 * The outcome of a charge, as {@code order} sees it.
 *
 * <p>Exposed as flags rather than a status enum on purpose: {@code order} needs to decide what to do,
 * not to reason about the gateway's state machine, and the payment lifecycle stays this module's
 * business.
 *
 * <p>{@code retryable} is the field that matters. It distinguishes "try another card" — the hold is
 * kept, the buyer retries — from "stop". An earlier design collapsed both into one failure event
 * whose documented behaviour was to release the hold, contradicting the very UX it was meant to
 * serve.
 *
 * <p>{@code clientSecret} is populated only alongside {@code requiresAction}, and it is the one
 * value here the browser ever sees: it is what {@code stripe.handleNextAction} needs to run the
 * 3-D Secure challenge. It is scoped to a single PaymentIntent and confers nothing else.
 */
public record PaymentResult(
        String transactionReference,
        boolean succeeded,
        String gatewayReference,
        String clientSecret,
        String failureCode,
        String failureReason,
        boolean retryable,
        boolean requiresAction) {}
