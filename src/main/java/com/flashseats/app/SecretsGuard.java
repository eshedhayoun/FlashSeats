package com.flashseats.app;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Refuses to start with development secrets outside {@code dev}/{@code test} (ADR-039). The default
 * strings would let anyone forge every capability token and the admin login. A default secret breaks
 * nothing visible, so a startup that stops is the only signal nobody scrolls past.
 *
 * <p>It reads the {@link Environment}, not the modules' {@code *Properties}, which are
 * module-internal.
 */
@Configuration
@Profile("!dev & !test")
public class SecretsGuard {

    private static final String DEFAULT_SECRET = "dev-only-change-me";

    /**
     * The shortest signing key accepted: 256 bits of base64, the HMAC-SHA256 block. A token is free to
     * obtain, so a short key can be searched offline against one, and every capability it signs is
     * then forgeable (ADR-076).
     */
    static final int MIN_SIGNING_SECRET_LENGTH = 32;

    /**
     * The admin password must name an adaptive hash. The guard accepts the <strong>encoding</strong>,
     * not a value (ADR-048): {@code {noop}} is plaintext, the {@code MD5}/{@code SHA-*} family is fast
     * enough to brute-force, and an unprefixed value — the compose default {@code admin} — names no
     * encoder at all, so it would start cleanly and fail every login (ADR-076).
     */
    private static final Pattern HASHED_PASSWORD =
            Pattern.compile("^\\{(bcrypt|argon2|pbkdf2|scrypt)(@[^}]*)?}.+");

    /** Property, the environment variable that supplies it, and the check its value must pass. */
    private record Secret(String property, String envVar, Predicate<String> acceptable) {

        boolean isUnacceptable(String value) {
            return value == null || value.isBlank() || !acceptable.test(value);
        }
    }

    private static Secret signingSecret(String property, String envVar) {
        return new Secret(
                property,
                envVar,
                value -> !DEFAULT_SECRET.equals(value) && value.length() >= MIN_SIGNING_SECRET_LENGTH);
    }

    /** The three keys that sign capability tokens. Each must also differ from the others. */
    private static final List<Secret> SIGNING = List.of(
            signingSecret("flashseats.session.secret", "FLASHSEATS_SESSION_SECRET"),
            signingSecret("flashseats.queue.pass-secret", "FLASHSEATS_QUEUE_PASS_SECRET"),
            signingSecret("flashseats.order.receipt-secret", "FLASHSEATS_RECEIPT_SECRET"));

    private static final Secret ADMIN_PASSWORD = new Secret(
            "flashseats.admin.password",
            "FLASHSEATS_ADMIN_PASSWORD",
            value -> HASHED_PASSWORD.matcher(value).matches());

    /**
     * Guarded only once the real provider is switched on: the stub needs no keys. With Stripe on, both
     * values matter, because a published webhook secret fails every real delivery and a lost charge
     * response then never reaches an order.
     */
    private static final List<Secret> GUARDED_WITH_STRIPE = List.of(
            new Secret("flashseats.payment.stripe.api-key", "STRIPE_API_KEY", value -> !"sk_test_...".equals(value)),
            new Secret(
                    "flashseats.payment.stripe.webhook-secret",
                    "STRIPE_WEBHOOK_SECRET",
                    value -> !"whsec_dev_only_change_me".equals(value)));

    private final Environment environment;

    public SecretsGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void requireRealSecrets() {
        List<Secret> guarded = new ArrayList<>(SIGNING);
        guarded.add(ADMIN_PASSWORD);
        if (environment.getProperty("flashseats.payment.stripe.enabled", Boolean.class, false)) {
            guarded.addAll(GUARDED_WITH_STRIPE);
        }

        List<String> offenders = new ArrayList<>(guarded.size());
        for (Secret secret : guarded) {
            if (secret.isUnacceptable(environment.getProperty(secret.property()))) {
                offenders.add(secret.property() + "  (set " + secret.envVar() + ")");
            }
        }
        // One key per token domain, so one leak forges one kind of token, not three (ADR-039). The
        // signed kind keeps a shared key from crossing domains, but not from leaking all of them.
        Set<String> distinct = new HashSet<>();
        for (Secret secret : SIGNING) {
            String value = environment.getProperty(secret.property());
            if (value != null && !value.isBlank() && !distinct.add(value)) {
                offenders.add(secret.property() + "  (set " + secret.envVar() + " to its own value)");
            }
        }
        if (offenders.isEmpty()) {
            return;
        }

        throw new IllegalStateException(
                """
                Refusing to start on profile(s) [%s] with development secrets in place:

                  - %s

                Each of these signs a capability token or guards the admin surface, and the default \
                values are published in this repository. Anyone who knows them can forge a session, \
                a queue pass, an admission and a receipt link for any buyer. Generate a distinct \
                random value, at least %d characters, per secret and per environment:

                  openssl rand -base64 48

                FLASHSEATS_ADMIN_PASSWORD is different: it must be HASHED with bcrypt, argon2, pbkdf2 \
                or scrypt and carry that prefix. A {noop} value is plaintext and an unprefixed one \
                names no encoder, whatever the password itself is. Produce one with:

                  docker run --rm httpd:alpine htpasswd -bnBC 12 "" 'your-password' | tr -d ':\\n'

                and store it as {bcrypt}$2y$12$... — or just run docker/secrets/gen-env.sh, which \
                does both. See docs/06-mvp-overview.md section 10."""
                        .formatted(
                                String.join(", ", environment.getActiveProfiles()),
                                String.join("\n  - ", offenders),
                                MIN_SIGNING_SECRET_LENGTH));
    }
}
