package com.flashseats.flashseats.config;

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
    private static final String DEFAULT_ADMIN_PASSWORD = "admin";

    /** Property, and the environment variable that supplies it. */
    private record Secret(String property, String envVar, String forbidden) {}

    private static final List<Secret> GUARDED = List.of(
            new Secret("flashseats.bot.session-secret", "FLASHSEATS_SESSION_SECRET", DEFAULT_SECRET),
            new Secret("flashseats.queue.pass-secret", "FLASHSEATS_QUEUE_PASS_SECRET", DEFAULT_SECRET),
            new Secret("flashseats.order.receipt-secret", "FLASHSEATS_RECEIPT_SECRET", DEFAULT_SECRET),
            new Secret("flashseats.admin.password", "FLASHSEATS_ADMIN_PASSWORD", DEFAULT_ADMIN_PASSWORD));

    private final Environment environment;

    public SecretsGuard(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    void requireRealSecrets() {
        List<String> offenders = new ArrayList<>(GUARDED.size());
        for (Secret secret : GUARDED) {
            String value = environment.getProperty(secret.property());
            if (value == null || value.isBlank() || secret.forbidden().equals(value)) {
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

                and supply it through the environment variable named above. See docs/06-mvp-overview.md \
                section 10."""
                        .formatted(
                                String.join(", ", environment.getActiveProfiles()),
                                String.join("\n  - ", offenders)));
    }
}
