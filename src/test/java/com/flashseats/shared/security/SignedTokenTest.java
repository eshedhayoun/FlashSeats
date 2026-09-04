package com.flashseats.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The signing primitive behind all four capability tokens: the session cookie, the queue pass, the
 * admission session and the order receipt.
 */
@DisplayName("SignedToken")
class SignedTokenTest {

    private static final String SECRET = "a-secret-that-is-long-enough";

    @Test
    @DisplayName("round-trips a payload")
    void roundTrips() {
        String token = SignedToken.sign("session-42", SECRET);
        assertThat(SignedToken.verify(token, SECRET)).contains("session-42");
    }

    @Test
    @DisplayName("rejects a tampered payload")
    void rejectsTamperedPayload() {
        String token = SignedToken.sign("session-42", SECRET);
        String forged = SignedToken.sign("session-99", SECRET).split("\\.")[0]
                + "." + token.split("\\.")[1];

        assertThat(SignedToken.verify(forged, SECRET)).isEmpty();
    }

    @Test
    @DisplayName("rejects a token signed with a different secret")
    void rejectsForeignSignature() {
        String token = SignedToken.sign("session-42", "another-secret");
        assertThat(SignedToken.verify(token, SECRET)).isEmpty();
    }

    @Test
    @DisplayName("returns empty rather than throwing on malformed input")
    void survivesGarbage() {
        // A tampered cookie is an ordinary event, not an exceptional one: the filter replaces it
        // with a fresh identity, and an exception here would strand the visitor instead.
        for (String garbage : new String[] {null, "", ".", "no-dot", "!!!.???", "a.b.c"}) {
            assertThat(SignedToken.verify(garbage, SECRET)).isEqualTo(Optional.empty());
        }
    }
}
