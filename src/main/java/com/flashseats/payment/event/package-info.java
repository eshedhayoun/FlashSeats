/**
 * {@code payment}'s published events — the asynchronous half of its contract.
 *
 * <p>Separate from {@code facade} because the direction is opposite: {@code facade} is what
 * {@code order} calls, this is what {@code order} listens to. Keeping them apart makes the one legal
 * inbound edge into {@code order} visible in the package structure rather than only in an ADR.
 */
@org.springframework.modulith.NamedInterface("event")
package com.flashseats.payment.event;
