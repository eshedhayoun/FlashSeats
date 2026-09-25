package com.flashseats.shared.identity;

/**
 * The verified visitor identity, from the signed {@code fsid} cookie and nothing else, because a
 * client-supplied identity would let anyone act as anyone (ADR-010).
 * {@link SessionIdentityFilter} verifies the cookie and publishes the id as a request attribute;
 * controllers declare a {@code SessionId} parameter, resolved by {@link SessionIdArgumentResolver}.
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
