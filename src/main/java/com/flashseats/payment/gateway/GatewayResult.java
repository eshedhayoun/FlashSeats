package com.flashseats.payment.gateway;

/**
 * What the provider answered.
 *
 * <p>{@link Outcome#DECLINED} and {@link Outcome#ERROR} are kept apart deliberately. A decline is a
 * <em>correct answer</em>, not a fault: retrying it triples the fraud signal against the customer's
 * card and changes nothing. Only transport failures may be retried (global standards §6).
 */
public record GatewayResult(
        Outcome outcome, String gatewayReference, String failureCode, String failureReason) {

    public enum Outcome {
        /** Money moved. */
        SUCCEEDED,
        /** The card was refused. The buyer should try another one — and keeps their seats. */
        DECLINED,
        /** 3-D Secure or similar. Unused until Stripe replaces the stub. */
        REQUIRES_ACTION,
        /** The provider was unreachable or errored. Retryable at the transport level. */
        ERROR
    }

    public static GatewayResult succeeded(String gatewayReference) {
        return new GatewayResult(Outcome.SUCCEEDED, gatewayReference, null, null);
    }

    public static GatewayResult declined(String code, String reason) {
        return new GatewayResult(Outcome.DECLINED, null, code, reason);
    }

    public static GatewayResult error(String code, String reason) {
        return new GatewayResult(Outcome.ERROR, null, code, reason);
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCEEDED;
    }
}
