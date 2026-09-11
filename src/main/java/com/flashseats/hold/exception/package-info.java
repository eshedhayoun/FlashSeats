/**
 * The failures {@code hold}'s facade raises.
 *
 * <p>{@code order} in particular must be able to name {@link
 * com.flashseats.hold.exception.HoldAlreadySettledException} and {@link
 * com.flashseats.hold.exception.HoldExpiredException}: both are decision points in the checkout
 * sequence, not merely errors to propagate.
 */
@org.springframework.modulith.NamedInterface("exception")
package com.flashseats.hold.exception;
