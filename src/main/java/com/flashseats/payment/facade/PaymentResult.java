package com.flashseats.payment.facade;

/**
 * The outcome of a charge, as {@code order} sees it: flags rather than the gateway's status enum.
 * {@code retryable} separates "try another card" (the hold is kept) from "stop".
 * {@code clientSecret} is set only with {@code requiresAction}; it is the one value the browser
 * sees, and it drives the 3-D Secure challenge for a single PaymentIntent.
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
