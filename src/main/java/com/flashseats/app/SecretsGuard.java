package com.flashseats.app;

import jakarta.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

/**
 * Refuses to start with development secrets in a non-development environment.
 *
 * <p>Four values sign every capability in the system: the {@code fsid} cookie, the queue pass, the
 * admission session, and the receipt token. Anyone holding the default string can forge all four —
 * which is not "weak authentication" but total impersonation of any buyer, plus the ability to read
 * any order. The admin password guards pre-warm and the metrics endpoints on the same terms.
 *
 * <p><strong>A warning would not have been enough</strong> (ADR-039). This failure is silent by
 * nature: everything works perfectly with a default secret, so nothing about a running system
 * reveals the problem until someone exploits it. A startup that stops is the only signal that
 * cannot be scrolled past.
 *
 * <p>Not active on {@code dev} or {@code test}, where the defaults are the point: the stack has to
 * run from a clean checkout with no configuration, and the tests need deterministic secrets so a
 * token minted in one place verifies in another.
 *
 * <p>Reads the {@link Environment} rather than the modules' {@code *Properties} beans on purpose.
 * A {@code config} package is module-internal, and reaching into three of them from the bootstrap
 * package is a boundary violation {@code ModularityTests} correctly rejects. Resolved property
 * values are configuration, not another module's API — and checking them is also closer to what
 * this class actually means: whatever the deployment ended up with, is it still the published
 * default?
 */
@Configuration
@Profile("!dev & !test")
public class SecretsGuard {

    private static final String DEFAULT_SECRET = "dev-only-change-me";

    /**
     * Spring Security's marker for "this password is not hashed".
     *
     * <p>The admin password is checked for <strong>this prefix</strong> rather than for the literal
     * string {@code admin} it used to carry. That is a strictly stronger test and the reason for the
     * change: the old check refused exactly one known value, so {@code hunter2} sailed through and
     * was stored and compared in plaintext. Rejecting the encoding instead means every plaintext
     * password is refused, whether or not anyone thought to list it.
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
     * Guarded only once the real provider is switched on.
     *
     * <p>Unconditional would be wrong in both directions. A deployment running the stub has no
     * Stripe account and nothing to set, so demanding keys would refuse to start a perfectly
     * coherent configuration. A deployment running Stripe with the published webhook secret is worse
     * than one with none: every real delivery fails its signature check, so a charge whose response
     * was lost never reaches an order, and the symptom is silence that looks exactly like a quiet
     * day. Both values are checked, because an API key with no usable webhook secret is a system
     * that can take money and cannot finish the sale.
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
