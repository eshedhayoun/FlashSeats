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
    private static final String KIND = "fsid";

    @Test
    @DisplayName("round-trips a payload")
    void roundTrips() {
        String token = SignedToken.sign(KIND, "session-42", SECRET);
        assertThat(SignedToken.verify(KIND, token, SECRET)).contains("session-42");
    }

    @Test
    @DisplayName("rejects a tampered payload")
    void rejectsTamperedPayload() {
        String token = SignedToken.sign(KIND, "session-42", SECRET);
        String forged = SignedToken.sign(KIND, "session-99", SECRET).split("\\.")[0]
                + "." + token.split("\\.")[1];

        assertThat(SignedToken.verify(KIND, forged, SECRET)).isEmpty();
    }

    @Test
    @DisplayName("rejects a token signed with a different secret")
    void rejectsForeignSignature() {
        String token = SignedToken.sign(KIND, "session-42", "another-secret");
        assertThat(SignedToken.verify(KIND, token, SECRET)).isEmpty();
    }

    @Test
    @DisplayName("rejects a token of another kind, even on the same secret")
    void rejectsForeignKind() {
        // The point of domain separation (ADR-039). Deployments may reuse a secret across token
        // types by accident or by configuration; the kind is what stops a receipt link being
        // presented as a session cookie, or a queue pass as an admission.
        String receipt = SignedToken.sign("receipt", "TK-00001", SECRET);

        assertThat(SignedToken.verify("fsid", receipt, SECRET)).isEmpty();
        assertThat(SignedToken.verify("pass", receipt, SECRET)).isEmpty();
        assertThat(SignedToken.verify("receipt", receipt, SECRET)).contains("TK-00001");
    }

    @Test
    @DisplayName("the kind cannot be smuggled through the payload")
    void kindIsNotConfusableWithPayload() {
        // The domain is prefixed to the signed bytes, so a payload that starts with another kind's
        // name must not produce the same signature as that kind over a shorter payload.
        String a = SignedToken.sign("pass", "admit x", SECRET);
        String b = SignedToken.sign("pass admit", "x", SECRET);

        assertThat(a.split("\\.")[1]).isNotEqualTo(b.split("\\.")[1]);
    }

    @Test
    @DisplayName("returns empty rather than throwing on malformed input")
    void survivesGarbage() {
        // A tampered cookie is an ordinary event, not an exceptional one: the filter replaces it
        // with a fresh identity, and an exception here would strand the visitor instead.
        for (String garbage : new String[] {null, "", ".", "no-dot", "!!!.???", "a.b.c"}) {
            assertThat(SignedToken.verify(KIND, garbage, SECRET)).isEqualTo(Optional.empty());
        }
    }
}
