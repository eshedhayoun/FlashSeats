package com.flashseats.payment.service;

/**
 * A charge already at the provider that the buyer was sent away to authenticate.
 *
 * <p>A record rather than the entity, because {@link PaymentTransactionStore} reads it in its own
 * short transaction and everything after that point runs detached — handing a detached entity across
 * a network call is how a lazy field becomes a {@code LazyInitializationException} in the middle of
 * taking someone's money.
 */
public record ResumableCharge(String transactionReference, String gatewayReference) {}
