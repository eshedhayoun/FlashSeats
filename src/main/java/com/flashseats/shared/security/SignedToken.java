package com.flashseats.shared.security;

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
 * <p>This is a crypto primitive, not a policy: each module decides what it signs and for how long.
 */
public final class SignedToken {

    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private SignedToken() {}

    /** Returns {@code base64url(payload).base64url(hmac)}. */
    public static String sign(String payload, String secret) {
        byte[] raw = payload.getBytes(StandardCharsets.UTF_8);
        return ENCODER.encodeToString(raw) + "." + ENCODER.encodeToString(hmac(raw, secret));
    }

    /**
     * Verifies the signature and returns the payload, or {@link Optional#empty()} if the token is
     * malformed or the signature does not match. Never throws on bad input — a tampered token is an
     * ordinary outcome, not an exceptional one.
     */
    public static Optional<String> verify(String token, String secret) {
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
            if (!MessageDigest.isEqual(hmac(payload, secret), presented)) {
                return Optional.empty();
            }
            return Optional.of(new String(payload, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException malformedBase64) {
            return Optional.empty();
        }
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
