package com.flashseats.order.model;

/**
 * <pre>
 *   (none) ──► PENDING ──► CONFIRMED    terminal, success
 *                │  ▲
 *                │  └── retry after a decline, on the SAME order number
 *                ├──► FAILED            retryable; the hold is deliberately retained
 *                └──► REFUNDED          terminal; charged, but the seats could not be delivered
 *                        └──► REFUND_FAILED   and the provider refused to give the money back
 * </pre>
 *
 * <p>Every transition is a compare-and-set on this row (ADR-064). {@code REFUND_FAILED} is money owed
 * to a named buyer: it is never announced as refunded, and {@code flashseats.payment.refund.failed}
 * counts it (ADR-069).
 *
 * <p>{@code FAILED} keeping the hold is the whole point of the decline path: the UX promises the
 * buyer can try another card, and releasing their seats would contradict it.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    FAILED,
    REFUNDED,
    REFUND_FAILED
}
