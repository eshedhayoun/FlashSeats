package com.flashseats.payment.gateway;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

import io.github.resilience4j.retry.Retry;
/**
 * A circuit breaker around whichever gateway is configured (ADR-052). It is a decorator, because
 * Resilience4j's starter targets Boot 3, so the plain artifacts are used.
 *
 * <p><strong>Only {@link GatewayTransportException} is counted.</strong> A decline is a returned
 * {@link GatewayResult}: a breaker that counted declines would open on an ordinary burst of expired
 * cards. Transport failures and an open breaker both leave as {@link GatewayResult#error}, which
 * becomes {@code 503 PAYMENT_GATEWAY_UNAVAILABLE}: seats retained, no attempt consumed.
 */
@Slf4j
public class CircuitBreakingGateway implements PaymentGateway {

    private final PaymentGateway delegate;
    private final CircuitBreaker breaker;
    private final Retry retry;

    public CircuitBreakingGateway(
            PaymentGateway delegate,
            CircuitBreaker breaker,
            Retry retry) {
        this.delegate = delegate;
        this.breaker = breaker;
        this.retry = retry;
    }

    @Override
    public GatewayResult charge(GatewayCharge charge) {
        return guard("charge", () -> delegate.charge(charge));
    }

    @Override
    public GatewayResult retrieve(String gatewayReference) {
        return guard("retrieve", () -> delegate.retrieve(gatewayReference));
    }

    @Override
    public GatewayResult refund(String gatewayReference, long amountCents, String reason) {
        return guard("refund", () -> delegate.refund(gatewayReference, amountCents, reason));
    }

    private GatewayResult guard(String operation, Supplier<GatewayResult> call) {
        try {
            return breaker.executeSupplier(
                    () -> retry.executeSupplier(call));
        } catch (CallNotPermittedException open) {
            log.warn(
                    "Payment gateway circuit is OPEN; refusing {} without calling the provider",
                    operation);
            return GatewayResult.error(
                    "circuit_open",
                    "The payment provider is unavailable. Your seats are still held.");
        } catch (GatewayTransportException transport) {
            return GatewayResult.error(
                    transport.code(),
                    transport.getMessage());
        }
    }
}
