package com.flashseats.app;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
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
     * Spring Security's marker for "this password is not hashed". The guard refuses the
     * <strong>encoding</strong>, not a known value, so every plaintext password is rejected (ADR-048).
     */
    private static final String PLAINTEXT_PREFIX = "{noop}";

    /**
     * Property, the environment variable that supplies it, and what disqualifies the value —
     * {@code null} on {@code forbidden} means "reject any {@code {noop}} encoding".
     */
    private record Secret(String property, String envVar, String forbidden) {

        boolean isUnacceptable(String value) {
            if (value == null || value.isBlank()) {
                return true;
            }
            return forbidden == null ? value.startsWith(PLAINTEXT_PREFIX) : forbidden.equals(value);
        }
    }

    private static final List<Secret> GUARDED = List.of(
            new Secret("flashseats.session.secret", "FLASHSEATS_SESSION_SECRET", DEFAULT_SECRET),
            new Secret("flashseats.queue.pass-secret", "FLASHSEATS_QUEUE_PASS_SECRET", DEFAULT_SECRET),
            new Secret("flashseats.order.receipt-secret", "FLASHSEATS_RECEIPT_SECRET", DEFAULT_SECRET),
            new Secret("flashseats.admin.password", "FLASHSEATS_ADMIN_PASSWORD", null));

    /**
     * Guarded only once the real provider is switched on: the stub needs no keys. With Stripe on, both
     * values matter, because a published webhook secret fails every real delivery and a lost charge
     * response then never reaches an order.
     */
    private static final List<Secret> GUARDED_WITH_STRIPE = List.of(
            new Secret("flashseats.payment.stripe.api-key", "STRIPE_API_KEY", "sk_test_..."),
            new Secret(
                    "flashseats.payment.stripe.webhook-secret",
                    "STRIPE_WEBHOOK_SECRET",
                    "whsec_dev_only_change_me"));

    private final Environment environment;

    public SecretsGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void requireRealSecrets() {
        List<Secret> guarded = new ArrayList<>(GUARDED);
        if (environment.getProperty("flashseats.payment.stripe.enabled", Boolean.class, false)) {
            guarded.addAll(GUARDED_WITH_STRIPE);
        }

        List<String> offenders = new ArrayList<>(guarded.size());
        for (Secret secret : guarded) {
            if (secret.isUnacceptable(environment.getProperty(secret.property()))) {
                offenders.add(secret.property() + "  (set " + secret.envVar() + ")");
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
                random value per environment:

                  openssl rand -base64 48

                FLASHSEATS_ADMIN_PASSWORD is different: it must be HASHED, and is rejected while it \
                carries the {noop} prefix that means plaintext — whatever the password itself is. \
                Produce one with:

                  docker run --rm httpd:alpine htpasswd -bnBC 12 "" 'your-password' | tr -d ':\\n'

                and store it as {bcrypt}$2y$12$... — or just run docker/secrets/gen-env.sh, which \
                does both. See docs/06-mvp-overview.md section 10."""
                        .formatted(
                                String.join(", ", environment.getActiveProfiles()),
                                String.join("\n  - ", offenders)));
    }
}
