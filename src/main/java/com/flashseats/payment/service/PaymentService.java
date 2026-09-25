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
         * One counter, tagged by outcome, rather than a decline RATIO.
         *
         * The ratio this replaced was cumulative since JVM start -- declines divided
         * by all attempts, for the life of the process. 03 section 7 says the metric
         * exists because "a spike is either a provider incident or a fraud rule
         * mis-firing", and a lifetime ratio is the one shape that cannot show a
         * spike: every sample dilutes the next, so an outage at hour six barely
         * moves a number six hours of healthy traffic have flattened.
         *
         * Counters leave the windowing to whoever is asking, which is where it
         * belongs: rate(attempts{outcome="declined"}[5m]) / rate(attempts[5m]) is
         * the decline ratio over any window, and the same series answers "are we
         * reaching the provider at all" without a second metric. Outcome is a
         * four-value enum, so the tag cannot explode.
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
         * Refunds here are full-refund operations, so a ledger that already records
         * this amount means the work is done and the provider round trip is waste.
         * Webhook redelivery and application retry both arrive this way.
         *
         * What this does NOT protect against is the interesting case: a crash after
         * the provider refunded but before our DB recorded it. There
         * refundedAmountCents is still zero, so this guard does not fire at all —
         * and it must not, because we genuinely do not know the money moved. That
         * case is covered one layer down, by the idempotency key
         * StripePaymentGateway.refund sets over (intent, amount): the retried
         * request returns the provider's original result instead of refunding twice.
         *
         * Stated explicitly because a guard whose comment claims a guarantee it does
         * not hold is how the layer that actually holds it gets removed later.
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
