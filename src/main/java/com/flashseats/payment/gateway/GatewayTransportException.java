package com.flashseats.payment.gateway;

/**
 * The provider could not be reached, or answered something that is not about the card. It exists
 * to be counted: it is the one signal the circuit breaker records, because a decline is a returned
 * value (ADR-052). {@link CircuitBreakingGateway} converts it to {@link GatewayResult#error}.
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
