package com.flashseats.order.model;

/**
 * <pre>
 *   (none) ──► PENDING ──► CONFIRMED    terminal, success
 *                │  ▲
 *                │  └── retry after a decline, on the SAME order number
 *                ├──► FAILED            retryable; the hold is deliberately retained
 *                └──► REFUNDED          terminal; charged, but the seats could not be delivered
 * </pre>
 *
 * <p>{@code FAILED} keeping the hold is the whole point of the decline path: the UX promises the
 * buyer can try another card, and releasing their seats would contradict it.
 */
public enum OrderStatus {
    PENDING,
    CONFIRMED,
    FAILED,
    REFUNDED
}
