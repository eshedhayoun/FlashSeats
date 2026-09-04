package com.flashseats.payment.service;

import com.flashseats.payment.config.PaymentProperties;
import com.flashseats.payment.exception.DuplicatePaymentException;
import com.flashseats.payment.exception.PaymentGatewayUnavailableException;
import com.flashseats.payment.facade.AuthorizeCommand;
import com.flashseats.payment.facade.PaymentResult;
import com.flashseats.payment.facade.RefundResult;
import com.flashseats.payment.gateway.GatewayCharge;
import com.flashseats.payment.gateway.GatewayResult;
import com.flashseats.payment.gateway.PaymentGateway;
import com.flashseats.payment.model.PaymentTransaction;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Charging and refunding.
 *
 * <p><strong>No method here is {@code @Transactional}, deliberately.</strong> Each brackets a network
 * call with two short transactions owned by {@link PaymentTransactionStore}, so no pooled connection
 * is ever held across the provider round trip (ADR-023).
 */
@Service
public class PaymentService {

    /** Owned by this module. Nothing else reads or writes this prefix. */
    private static final String INFLIGHT_KEY = "payment:inflight:";

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentGateway gateway;
    private final PaymentTransactionStore store;
    private final StringRedisTemplate redis;
    private final PaymentProperties properties;

    public PaymentService(
            PaymentGateway gateway,
            PaymentTransactionStore store,
            StringRedisTemplate redis,
            PaymentProperties properties) {
        this.gateway = gateway;
        this.store = store;
        this.redis = redis;
        this.properties = properties;
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
    public PaymentResult authorize(AuthorizeCommand command) {
        String inflightKey = INFLIGHT_KEY + command.holdToken();
        Boolean acquired = redis.opsForValue()
                .setIfAbsent(
                        inflightKey,
                        command.orderNumber(),
                        Duration.ofSeconds(properties.getInflightTtlSeconds()));

        if (!Boolean.TRUE.equals(acquired)) {
            throw new DuplicatePaymentException(command.holdToken());
        }

        try {
            PaymentTransaction transaction = store.recordInitiated(command); // tx1, committed

            GatewayResult result = gateway.charge(new GatewayCharge( // no transaction open
                    command.orderNumber(),
                    command.amountCents(),
                    command.currency(),
                    command.paymentMethodId(),
                    command.clientIdempotencyKey()));

            store.recordOutcome(transaction.getTransactionReference(), result); // tx2

            if (result.outcome() == GatewayResult.Outcome.ERROR) {
                log.warn("Gateway error for order {}: {}", command.orderNumber(), result.failureReason());
                throw new PaymentGatewayUnavailableException(result.failureReason());
            }

            return new PaymentResult(
                    transaction.getTransactionReference(),
                    result.isSuccess(),
                    result.gatewayReference(),
                    result.failureCode(),
                    result.failureReason(),
                    result.outcome() == GatewayResult.Outcome.DECLINED,
                    result.outcome() == GatewayResult.Outcome.REQUIRES_ACTION);
        } finally {
            // Released whatever happened. The order row remains the durable guard, so letting go
            // early costs nothing and avoids stranding a buyer behind their own failed attempt.
            redis.delete(inflightKey);
        }
    }

    /** Compensation for a charge that settled against seats we could not deliver (ADR-012). */
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
