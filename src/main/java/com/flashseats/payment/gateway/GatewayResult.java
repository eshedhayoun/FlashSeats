package com.flashseats.payment.gateway;

/**
 * What the provider answered.
 *
 * <p>Three outcomes, each produced by a real code path. A fourth, {@code REQUIRES_ACTION}, was
 * declared for 3-D Secure and never returned by anything; it comes back with the flow that raises
 * it.
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
