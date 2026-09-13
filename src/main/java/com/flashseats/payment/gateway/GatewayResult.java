package com.flashseats.payment.gateway;

/**
 * What the provider answered.
 *
 * <p>{@link Outcome#DECLINED} and {@link Outcome#ERROR} are kept apart deliberately. A decline is a
 * <em>correct answer</em>, not a fault: retrying it triples the fraud signal against the customer's
 * card and changes nothing. Only transport failures may be retried (global standards §6).
 */
public record GatewayResult(
        Outcome outcome,
        String gatewayReference,
        String clientSecret,
        String failureCode,
        String failureReason) {

    public enum Outcome {
        /** Money moved. */
        SUCCEEDED,
        /** The card was refused. The buyer should try another one — and keeps their seats. */
        DECLINED,
        /** 3-D Secure or similar: the buyer must authenticate before the charge can complete. */
        REQUIRES_ACTION,
        /** The provider was unreachable or errored. Retryable at the transport level. */
        ERROR
    }

    public static GatewayResult succeeded(String gatewayReference) {
        return new GatewayResult(Outcome.SUCCEEDED, gatewayReference, null, null, null);
    }

    /**
     * The buyer has to authenticate. <strong>Both arguments matter.</strong>
     *
     * <p>{@code clientSecret} is what the browser hands to {@code stripe.handleNextAction}, and
     * {@code gatewayReference} is how the resume re-reads <em>this</em> intent rather than creating
     * a second one — which is why this factory sets it where {@link #declined} and {@link #error}
     * deliberately leave it null. A charge that requires action has already created a real intent at
     * the provider; forgetting its id is how a buyer ends up authenticating one charge and being
     * billed for another.
     */
    public static GatewayResult requiresAction(String gatewayReference, String clientSecret) {
        return new GatewayResult(Outcome.REQUIRES_ACTION, gatewayReference, clientSecret, null, null);
    }

    public static GatewayResult declined(String code, String reason) {
        return new GatewayResult(Outcome.DECLINED, null, null, code, reason);
    }

    public static GatewayResult error(String code, String reason) {
        return new GatewayResult(Outcome.ERROR, null, null, code, reason);
    }

    public boolean isSuccess() {
        return outcome == Outcome.SUCCEEDED;
    }

    public boolean requiresAction() {
        return outcome == Outcome.REQUIRES_ACTION;
    }
}
