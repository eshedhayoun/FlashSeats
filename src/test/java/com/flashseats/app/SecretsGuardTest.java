package com.flashseats.app;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/** What the startup guard refuses outside {@code dev}/{@code test} (ADR-039, ADR-048, ADR-076). */
@DisplayName("SecretsGuard refuses published, weak, shared and plaintext secrets")
class SecretsGuardTest {

    private static final String SESSION = "s".repeat(64);
    private static final String PASS = "p".repeat(64);
    private static final String RECEIPT = "r".repeat(64);
    private static final String BCRYPT = "{bcrypt}$2y$12$abcdefghijklmnopqrstuuJ1bH2yW2C4b5d6e7f8g9h0i1j2k3l4m";

    @Test
    @DisplayName("Three distinct long keys and a hashed admin password start")
    void realSecretsStart() {
        assertThatCode(() -> guard(environment()).requireRealSecrets()).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("The published default is refused")
    void publishedDefaultIsRefused() {
        assertRefused(environment().withProperty("flashseats.session.secret", "dev-only-change-me"),
                "flashseats.session.secret");
    }

    @Test
    @DisplayName("A signing key shorter than 256 bits of base64 is refused")
    void shortSigningKeyIsRefused() {
        assertRefused(environment().withProperty("flashseats.queue.pass-secret", "x".repeat(31)),
                "flashseats.queue.pass-secret");
    }

    @Test
    @DisplayName("Two token domains sharing one key are refused")
    void sharedSigningKeyIsRefused() {
        assertRefused(environment().withProperty("flashseats.order.receipt-secret", SESSION),
                "to its own value");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"admin", "{noop}hunter2", "{MD5}5f4dcc3b5aa765d61d8327deb882cf99",
            "{sha256}abcdef", "{SHA-1}abcdef", "{bcrypt}"})
    @DisplayName("An admin password that is not an adaptive hash is refused")
    void unhashedAdminPasswordIsRefused(String password) {
        assertRefused(environment().withProperty("flashseats.admin.password", password),
                "flashseats.admin.password");
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"{argon2}$argon2id$v=19$m=16384,t=2,p=1$c2FsdA$aGFzaA",
            "{argon2@SpringSecurity_v5_8}$argon2id$v=19$m=16384,t=2,p=1$c2FsdA$aGFzaA",
            "{pbkdf2@SpringSecurity_v5_8}0123456789abcdef", "{scrypt}$e0801$c2FsdA==$aGFzaA=="})
    @DisplayName("The other adaptive hashes are accepted")
    void otherAdaptiveHashesStart(String password) {
        assertThatCode(() -> guard(environment().withProperty("flashseats.admin.password", password))
                        .requireRealSecrets())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("With Stripe on, the sample API key is refused")
    void sampleStripeKeyIsRefusedWhenEnabled() {
        assertRefused(environment()
                        .withProperty("flashseats.payment.stripe.enabled", "true")
                        .withProperty("flashseats.payment.stripe.api-key", "sk_test_...")
                        .withProperty("flashseats.payment.stripe.webhook-secret", "whsec_real"),
                "flashseats.payment.stripe.api-key");
    }

    private static MockEnvironment environment() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("flashseats.session.secret", SESSION)
                .withProperty("flashseats.queue.pass-secret", PASS)
                .withProperty("flashseats.order.receipt-secret", RECEIPT)
                .withProperty("flashseats.admin.password", BCRYPT);
        environment.setActiveProfiles("docker");
        return environment;
    }

    private static SecretsGuard guard(MockEnvironment environment) {
        return new SecretsGuard(environment);
    }

    private static void assertRefused(MockEnvironment environment, String naming) {
        assertThatThrownBy(() -> guard(environment).requireRealSecrets())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(naming);
    }
}
