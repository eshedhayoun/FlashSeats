package com.flashseats.payment.service;

import com.flashseats.payment.config.PaymentProperties;
import com.flashseats.payment.exception.DuplicatePaymentException;
import com.flashseats.payment.exception.PaymentErrors;
import com.flashseats.payment.facade.AuthorizeCommand;
import com.flashseats.payment.facade.PaymentFacade;
import com.flashseats.payment.facade.PaymentResult;
import com.flashseats.payment.facade.RefundResult;
import com.flashseats.payment.gateway.GatewayCharge;
import com.flashseats.payment.gateway.GatewayResult;
import com.flashseats.payment.gateway.PaymentGateway;
import com.flashseats.payment.model.PaymentTransaction;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Charging and refunding; implements {@link PaymentFacade} (ADR-057). No method is
 * {@code @Transactional}: each network call is bracketed by two short transactions on
 * {@link PaymentTransactionStore} (ADR-023).
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
    private final Map<GatewayResult.Outcome, Counter> attemptsByOutcome =
            new EnumMap<>(GatewayResult.Outcome.class);

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
        /*
         * One counter tagged by outcome, not a lifetime ratio, which cannot show a spike.
         * rate(attempts{outcome="declined"}[5m]) / rate(attempts[5m]) is the ratio over any window.
         */
        for (GatewayResult.Outcome outcome : GatewayResult.Outcome.values()) {
            attemptsByOutcome.put(
                    outcome,
                    Counter.builder("flashseats.payment.attempts")
                            .description("Provider charge attempts by outcome")
                            .tag("outcome", outcome.name().toLowerCase())
                            .register(meters));
        }
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
            attemptsByOutcome.get(result.outcome()).increment();

            if (result.outcome() == GatewayResult.Outcome.ERROR) {
                log.warn("Gateway error for order {}: {}", command.orderNumber(), result.failureReason());
                throw PaymentErrors.gatewayUnavailable();
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

    @Override
    public RefundResult refund(String transactionReference, long amountCents, String reason) {
        PaymentTransaction transaction = store.require(transactionReference);
        /*
         * Full refunds only, so a ledger already recording this amount means the work is done. This does
         * NOT cover a crash after the provider refunded but before we recorded it; the idempotency key
         * StripePaymentGateway.refund sets over (intent, amount) covers that.
         */
        if (amountCents > 0 && transaction.getRefundedAmountCents() >= amountCents) {
            log.info(
                    "Refund for {} already recorded ({} cents); not calling provider again",
                    transactionReference,
                    transaction.getRefundedAmountCents());

            return new RefundResult(
                    transactionReference,
                    true,
                    amountCents,
                    null);
        }

        GatewayResult result =
                gateway.refund(
                        transaction.getGatewayReference(),
                        amountCents,
                        reason);

        if (result.isSuccess()) {
            store.recordRefund(transactionReference, amountCents);

            return new RefundResult(
                    transactionReference,
                    true,
                    amountCents,
                    null);
        }

        // A failed refund is money we hold and should not. It cannot be
        // resolved automatically yet, so keep it visible for reconciliation.
        log.error(
                "REFUND FAILED for {} ({} cents): {} — manual reconciliation required",
                transactionReference,
                amountCents,
                result.failureReason());

        return new RefundResult(
                transactionReference,
                false,
                0,
                result.failureReason());
    }
}
