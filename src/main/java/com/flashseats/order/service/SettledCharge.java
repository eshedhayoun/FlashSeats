package com.flashseats.order.service;

/**
 * One settled charge, as the path holding it knows it (ADR-075). {@code gatewayReference} is the
 * provider's id, the one both paths always have, so it is what makes two sightings the same charge.
 * {@code transactionReference} is the ledger row a refund is issued against; {@code null} when no
 * row matches, which leaves the charge for a human.
 */
public record SettledCharge(String transactionReference, String gatewayReference) {

    /** Whether the order row names this charge. An unknown reference on either side is never a match. */
    boolean isNamedBy(String gatewayReferenceOfRecord) {
        return gatewayReference != null && gatewayReference.equals(gatewayReferenceOfRecord);
    }
}
