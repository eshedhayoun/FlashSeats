package com.flashseats.shared.identity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * How the {@code fsid} cookie is minted and signed. Identity is the kernel, so this is
 * {@code flashseats.session.*}; the env var stays {@code FLASHSEATS_SESSION_SECRET}. Rotating
 * {@code secret} invalidates every live session: never mid-sale.
 */
@ConfigurationProperties(prefix = "flashseats.session")
@Getter
@Setter
public class SessionProperties {

    private String secret = "dev-only-change-me";
    private final Cookie cookie = new Cookie();

    @Getter
    @Setter
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
    }
}
