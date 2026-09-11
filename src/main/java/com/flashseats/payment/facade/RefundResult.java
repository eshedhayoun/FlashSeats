package com.flashseats.payment.facade;

/** The outcome of a compensating refund. */
public record RefundResult(
        String transactionReference, boolean succeeded, long refundedAmountCents, String failureReason) {}
