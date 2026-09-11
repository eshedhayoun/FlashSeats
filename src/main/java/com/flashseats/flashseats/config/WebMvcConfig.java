package com.flashseats.flashseats.config;

import com.flashseats.shared.identity.SessionIdArgumentResolver;
import java.util.List;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Registers the resolver that lets any controller declare a
 * {@link com.flashseats.shared.identity.SessionId} parameter and receive the verified {@code fsid}.
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final SessionIdArgumentResolver sessionIdArgumentResolver;

    public WebMvcConfig(SessionIdArgumentResolver sessionIdArgumentResolver) {
        this.sessionIdArgumentResolver = sessionIdArgumentResolver;
    }

    @Override
    public void addArgumentResolvers(List<HandlerMethodArgumentResolver> resolvers) {
        resolvers.add(sessionIdArgumentResolver);
    }
}
