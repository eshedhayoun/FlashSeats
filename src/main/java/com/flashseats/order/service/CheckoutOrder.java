package com.flashseats.order.service;

/**
 * The result of the find-or-create step.
 *
 * <p>{@code alreadyConfirmed} is how an idempotent replay is signalled: a client that submits the
 * same completed checkout twice gets its receipt back with {@code 200}, not an error. Replaying a
 * finished operation should return the original result (global standards §3).
 */
record CheckoutOrder(String orderNumber, int attemptNumber, boolean alreadyConfirmed) {}
