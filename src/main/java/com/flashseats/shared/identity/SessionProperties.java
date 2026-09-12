package com.flashseats.shared.identity;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the {@code fsid} cookie is minted and signed.
 *
 * <p><strong>These used to be {@code flashseats.bot.*}</strong>, because the filter that issues the
 * cookie lived in {@code bot}. That put the system's only source of identity inside the abuse-defence
 * module, and left the contract split in two: {@code bot} minted and signed, {@code shared} typed and
 * resolved, and the only thing joining them was a request-attribute string constant. Identity is not
 * abuse defence; it is the kernel, and this is where it belongs (ADR-010).
 *
 * <p>The environment variable is unchanged — {@code FLASHSEATS_SESSION_SECRET}, which is what
 * {@code .env}, {@code gen-env.sh} and {@code SecretsGuard} already name. Only the Spring property
 * path moved.
 *
 * <p>Rotating {@code secret} invalidates every live session, so it must not be rotated mid-sale.
 */
@ConfigurationProperties(prefix = "flashseats.session")
public class SessionProperties {

    private String secret = "dev-only-change-me";
    private final Cookie cookie = new Cookie();

    public String getSecret() {
        return secret;
    }

    public void setSecret(String secret) {
        this.secret = secret;
    }

    public Cookie getCookie() {
        return cookie;
    }

    public static class Cookie {
        private String name = "fsid";

        /**
         * A {@code Secure} cookie is dropped over plain HTTP, which would give every request a new
         * identity and silently break the entire flow. False for local development; true wherever
         * the app is served over TLS.
         */
        private boolean secure = false;

        private String sameSite = "Lax";
        private int maxAgeSeconds = 86_400;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public boolean isSecure() {
            return secure;
        }

        public void setSecure(boolean secure) {
            this.secure = secure;
        }

        public String getSameSite() {
            return sameSite;
        }

        public void setSameSite(String sameSite) {
            this.sameSite = sameSite;
        }

        public int getMaxAgeSeconds() {
            return maxAgeSeconds;
        }

        public void setMaxAgeSeconds(int maxAgeSeconds) {
            this.maxAgeSeconds = maxAgeSeconds;
        }
    }
}
