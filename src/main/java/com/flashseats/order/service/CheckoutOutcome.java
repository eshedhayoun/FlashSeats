package com.flashseats.order.service;

import com.flashseats.order.dto.OrderReceiptResponse;

/**
 * The receipt, plus whether this request created it.
 *
 * <p>{@code replayed} exists only to choose between {@code 201} and {@code 200}. Both are successes
 * and the client renders them identically — but a replayed checkout did not create a resource, and
 * saying otherwise would be a lie about what happened.
 */
public record CheckoutOutcome(OrderReceiptResponse receipt, boolean replayed) {}
