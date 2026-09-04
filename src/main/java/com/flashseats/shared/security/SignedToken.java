package com.flashseats.shared.security;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * HMAC-SHA256 signing for the four capability tokens in the system: the {@code fsid} session cookie
 * (ADR-010), the queue pass, the admission session token (ADR-020), and the order receipt token.
 *
 * <p>All four share the format {@code base64url(payload).base64url(hmac)}. One implementation means
 * one place for the constant-time comparison, and no risk of four hand-rolled variants drifting
 * apart.
 *
 * <p><strong>Every token declares its {@code kind}, and the kind is signed</strong> (ADR-039). The
 * kind is not stored in the token — it is mixed into the signed bytes — so a token minted as one
 * kind simply fails verification as another. Without it, two token types sharing a secret are
 * interchangeable to the verifier, and whether that is exploitable depends on payload formats
 * happening not to collide. That is a property nobody should have to re-derive after every change:
 * {@code queue} already carried a kind field for exactly this reason, and this makes it universal
 * rather than one module's good habit.
 *
 * <p>This is a crypto primitive, not a policy: each module decides what it signs and for how long.
 */
public final class SignedToken {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private SignedToken() {}

    /**
     * Returns {@code base64url(payload).base64url(hmac(kind, payload))}.
     *
     * @param kind the token's domain — {@code "fsid"}, {@code "pass"}, {@code "admit"},
     *     {@code "receipt"}. Signed, but not carried in the token.
     */
    public static String sign(String kind, String payload, String secret) {
        byte[] raw = payload.getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(raw) + "." + ENCODER.encodeToString(hmac(kind, raw, secret));
    }

    /**
     * Verifies the signature <em>for this kind</em> and returns the payload, or
     * {@link Optional#empty()} if the token is malformed, of another kind, or not signed by us.
     * Never throws on bad input — a tampered token is an ordinary outcome, not an exceptional one.
     */
    public static Optional<String> verify(String kind, String token, String secret) {
        if (token == null) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot < 1 || dot == token.length() - 1) {
            return Optional.empty();
        }
        try {
            byte[] payload = DECODER.decode(token.substring(0, dot));
            byte[] presented = DECODER.decode(token.substring(dot + 1));
            // Constant-time: a timing-variable compare leaks the expected signature byte by byte.
            if (!MessageDigest.isEqual(hmac(kind, payload, secret), presented)) {
                return Optional.empty();
            }
            return Optional.of(new String(payload, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformedBase64) {
            return Optional.empty();
        }
    }

    /**
     * Binds the kind to the payload before signing, unambiguously.
     *
     * <p>The kind is <strong>length-prefixed</strong> rather than delimited. A delimiter only works
     * while no kind can contain it — an invariant that lives in a comment and is one careless
     * constant away from being false. With a space, for instance, {@code ("pass", "admit x")} and
     * {@code ("pass admit", "x")} sign identical bytes, which is precisely the confusion domain
     * separation exists to prevent. A length prefix makes the boundary explicit for any kind and
     * any payload, including ones nobody has thought of yet.
     */
    private static byte[] hmac(String kind, byte[] payload, String secret) {
        byte[] domain = kind.getBytes(StandardCharsets.UTF_8);
        ByteBuffer bound = ByteBuffer.allocate(Integer.BYTES + domain.length + payload.length);
        bound.putInt(domain.length).put(domain).put(payload);
        return hmac(bound.array(), secret);
    }

    private static byte[] hmac(byte[] payload, String secret) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALGORITHM));
            return mac.doFinal(payload);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable or secret rejected", e);
        }
    }
}
