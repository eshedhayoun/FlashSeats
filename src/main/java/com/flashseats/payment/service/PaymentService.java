package com.flashseats.payment.service;

import com.flashseats.payment.config.PaymentProperties;
import com.flashseats.payment.exception.DuplicatePaymentException;
import com.flashseats.payment.exception.PaymentGatewayUnavailableException;
import com.flashseats.payment.facade.AuthorizeCommand;
import com.flashseats.payment.facade.PaymentFacade;
import com.flashseats.payment.facade.PaymentResult;
import com.flashseats.payment.facade.RefundResult;
import com.flashseats.payment.gateway.GatewayCharge;
import com.flashseats.payment.gateway.GatewayResult;
import com.flashseats.payment.gateway.PaymentGateway;
import com.flashseats.payment.model.PaymentTransaction;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Charging and refunding.
 *
 * <p><strong>No method here is {@code @Transactional}, deliberately.</strong> Each brackets a network
 * call with two short transactions owned by {@link PaymentTransactionStore}, so no pooled connection
 * is ever held across the provider round trip (ADR-023).
 *
 * <p>This class <em>is</em> {@link PaymentFacade}. Other modules see only that interface, because
 * this package is internal to the module and they may not name it. There is no separate delegating
 * implementation: one existed, held no logic, and only added a hop between the contract and the
 * code that honours it.
 */
@Slf4j
@Service
public class PaymentService implements PaymentFacade {

    /** Owned by this module. Nothing else reads or writes this prefix. */
    private static final String INFLIGHT_KEY = "payment:inflight:";

    private final PaymentGateway gateway;
    private final PaymentTransactionStore store;
    private final StringRedisTemplate redis;
    private final PaymentProperties properties;
    private final AtomicLong gatewayAttempts = new AtomicLong();
    private final AtomicLong declines = new AtomicLong();

    public PaymentService(
            PaymentGateway gateway,
            PaymentTransactionStore store,
            StringRedisTemplate redis,
            PaymentProperties properties,
            MeterRegistry meters) {
        this.gateway = gateway;
        this.store = store;
        this.redis = redis;
        this.properties = properties;
        Gauge.builder(
                        "flashseats.payment.decline.ratio",
                        this,
                        service -> service.declines.get() / (double) Math.max(1, service.gatewayAttempts.get()))
                .description("Declined provider attempts divided by all provider attempts")
                .register(meters);
    }

    /**
     * Attempts one charge, guarded against duplicates at three layers (ADR-014):
     *
     * <ol>
     *   <li>{@code UNIQUE(hold_token)} on {@code orders} — checked by {@code order} before we are
     *       called, and the <strong>actual guarantee</strong>
     *   <li>the short-lived key below — a fast path that stops a double-click cheaply
     *   <li>the client's key, forwarded to the provider for network-level retries
     * </ol>
     *
     * <p>Note which layer is the guarantee. Anchoring on the hold rather than on a client-chosen
     * string is what stops a client that regenerates its key on retry from bypassing the guard
     * entirely.
     */
    @Override
    public PaymentResult authorize(AuthorizeCommand command) {
        String inflightKey = INFLIGHT_KEY + command.holdToken();
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(
                        inflightKey,
                        command.orderNumber(),
                        Duration.ofSeconds(properties.getInflightTtlSeconds()));

        if (!Boolean.TRUE.equals(acquired)) {
            throw new DuplicatePaymentException();
        }

        try {
            // Resume before charging. If this hold was already sent away for 3-D Secure, the only
            // correct move is to re-read THAT intent: the client reuses one idempotency key for the
            // life of the hold (FE_SPEC §1), so a second charge would replay the provider's cached
            // "requires_action" answer for ever — and varying the key per attempt instead would
            // open a second intent and risk billing twice for one authentication.
            ChargeAttempt attempt = store.beginAttempt(command); // tx1

            gatewayAttempts.incrementAndGet();
            GatewayResult result = attempt.isResume() // no transaction open
                    ? gateway.retrieve(attempt.resumableGatewayReference())
                    : gateway.charge(new GatewayCharge(
                            command.orderNumber(),
                            command.holdToken(),
                            command.amountCents(),
                            command.currency(),
                            command.paymentMethodId(),
                            command.clientIdempotencyKey()));

            store.recordOutcome(attempt.transactionReference(), result); // tx2
            if (result.outcome() == GatewayResult.Outcome.DECLINED) {
                declines.incrementAndGet();
            }

            if (result.outcome() == GatewayResult.Outcome.ERROR) {
                log.warn("Gateway error for order {}: {}", command.orderNumber(), result.failureReason());
                throw new PaymentGatewayUnavailableException(result.failureReason());
            }

            return new PaymentResult(
                    attempt.transactionReference(),
                    result.isSuccess(),
                    result.gatewayReference(),
                    result.clientSecret(),
                    result.failureCode(),
                    result.failureReason(),
                    result.outcome() == GatewayResult.Outcome.DECLINED,
                    result.requiresAction());
        } finally {
            // Released whatever happened. The order row remains the durable guard, so letting go
            // early costs nothing and avoids stranding a buyer behind their own failed attempt.
            //
            // Guarded, because a throw from a `finally` REPLACES whatever the block was returning.
            // An unguarded delete meant that Redis dropping between the charge and this line
            // discarded a successful result and sent the caller down its catch-all to mark the
            // order FAILED — money moved, and the order says it did not. The key expires on its own
            // in `inflight-ttl-seconds`, so failing to release it costs nothing at all.
            try {
                redis.delete(inflightKey);
            } catch (RuntimeException releaseFailed) {
                log.warn(
                        "Could not release {}; it expires in {}s",
                        inflightKey,
                        properties.getInflightTtlSeconds(),
                        releaseFailed);
            }
        }
    }

    /** Compensation for a charge that settled against seats we could not deliver (ADR-012). */
    @Override
    public RefundResult refund(String transactionReference, long amountCents, String reason) {
        PaymentTransaction transaction = store.require(transactionReference);

        GatewayResult result =
                gateway.refund(transaction.getGatewayReference(), amountCents, reason);

        if (result.isSuccess()) {
            store.recordRefund(transactionReference, amountCents);
            return new RefundResult(transactionReference, true, amountCents, null);
        }

        // A failed refund is money we hold and should not. It cannot be resolved automatically.
        log.error(
                "REFUND FAILED for {} ({} cents): {} — manual reconciliation required",
                transactionReference,
                amountCents,
                result.failureReason());
        return new RefundResult(transactionReference, false, 0, result.failureReason());
    }
}
