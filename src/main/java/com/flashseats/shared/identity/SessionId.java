package com.flashseats.shared.identity;

/**
 * The verified visitor identity, taken from the signed {@code fsid} cookie and nothing else.
 *
 * <p>Queue position, hold ownership and order lookup all key off this value, so it may
 * <strong>never</strong> be read from a request body, query parameter or custom header — a
 * client-supplied identity would let anyone act as anyone (ADR-010).
 *
 * <p>The {@code bot} filter verifies the cookie's HMAC and publishes the id as a request attribute;
 * controllers receive it by declaring a {@code SessionId} parameter, resolved by
 * {@link SessionIdArgumentResolver}. That indirection is deliberate — no module needs to reference
 * {@code bot} to learn who is calling.
 */
public record SessionId(String value) {

    /** Request attribute under which the {@code bot} filter publishes the verified id. */
    public static final String REQUEST_ATTRIBUTE = "fsid";

    public SessionId {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("session id must not be blank");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
