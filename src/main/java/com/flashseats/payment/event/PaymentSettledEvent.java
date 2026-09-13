package com.flashseats.payment.event;

/**
 * A charge settled at the provider, reported by the webhook.
 *
 * <p><strong>The one cross-module event in the system</strong> (ADR-005), and the reason
 * {@code payment} calls no facade at all. The runtime direction here is {@code payment → order};
 * the <em>type</em> dependency runs the other way, {@code order → payment}, which already exists
 * because {@code order} drives checkout. A synchronous edge out of {@code payment} would close that
 * loop and fail {@code ApplicationModules.verify()}.
 *
 * <p>It exists for the case the synchronous path cannot cover: the charge succeeded and the buyer
 * never saw the response — a dropped connection, a killed replica, a closed laptop. The money moved
 * and nothing in this system knows it. {@code holdToken} is how the settlement finds its order,
 * carried out to the provider as metadata and back again (ADR-014).
 *
 * <p>Note what is <em>not</em> here: an amount to charge, or anything a caller could act on
 * financially. {@code amountCents} is the settled figure, reported for the refund arm only, and
 * {@code order} still prices from the tier (ADR-013).
 */
public record PaymentSettledEvent(
        String holdToken,
        String gatewayReference,
        String transactionReference,
        long amountCents,
        String currency) {}
