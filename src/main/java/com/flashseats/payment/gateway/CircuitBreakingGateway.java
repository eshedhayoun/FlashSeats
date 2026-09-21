package com.flashseats.payment.gateway;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
/**
 * A circuit breaker around whichever gateway is configured.
 *
 * <p>A decorator rather than an annotation, for two reasons. Resilience4j's Spring Boot starter
 * targets Boot 3 and is not on the classpath (this application declares the plain artifacts and one
 * {@link CircuitBreaker} bean by hand), and an AOP-driven breaker would have to decide what counts
 * as a failure from a <em>return value</em> — see below.
 *
 * <p><strong>Only {@link GatewayTransportException} is counted.</strong> A decline is a returned
 * {@link GatewayResult}, not a throw, and that asymmetry is the whole design: a breaker that counted
 * declines would open during an ordinary burst of expired cards and take a healthy sale's payments
 * down with it. What the breaker exists to stop is every buyer in a 10,000-person queue waiting out
 * a 20-second read timeout against a provider that is already down.
 *
 * <p>Both a transport failure and an open breaker leave by the same door — {@link GatewayResult#error}
 * — which the existing {@code ERROR → PAYMENT_GATEWAY_UNAVAILABLE → 503} path already handles: the
 * seats are retained and <strong>no payment attempt is consumed</strong>, exactly as
 * {@code 05-global-standards.md} §2 promises for that code. Nothing above this class changed to
 * accommodate the breaker.
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
