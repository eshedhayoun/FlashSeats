package com.flashseats.payment.gateway;

/**
 * The provider could not be reached, or answered something that is not an answer about the card.
 *
 * <p><strong>This type exists to be counted.</strong> It is the one signal
 * {@link CircuitBreakingGateway}'s breaker records, and the reason the breaker is not configured
 * with a {@code recordResult} predicate over {@link GatewayResult} instead: a decline is a
 * <em>returned value</em> here, and a breaker that counted declines would open during a burst of
 * ordinary expired cards and take the whole sale's payments down with it.
 *
 * <p>Thrown by gateway implementations, never seen above
 * {@link CircuitBreakingGateway}, which converts it to {@link GatewayResult#error} so that the
 * existing {@code ERROR → PAYMENT_GATEWAY_UNAVAILABLE → 503} path — seats retained, no attempt
 * consumed — keeps working unchanged.
 */
public class GatewayTransportException extends RuntimeException {

    private final String code;

    public GatewayTransportException(String code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public GatewayTransportException(String code, String message) {
        this(code, message, null);
    }

    public String code() {
        return code;
    }
}
