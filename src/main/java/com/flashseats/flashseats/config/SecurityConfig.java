package com.flashseats.flashseats.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Application-wide HTTP security.
 *
 * <p>This class is not optional. {@code spring-boot-starter-security} is on the classpath, and
 * without an explicit chain Boot's default would put <em>every</em> endpoint behind a generated
 * password — the whole sale included.
 *
 * <p>Two rules: {@code /api/v1/admin/**} requires {@code ROLE_ADMIN}, and everything else is open.
 * "Admin Only" in a module spec is an enforced role, not a comment (global standards §1).
 *
 * <p>The API is stateless — identity is the signed {@code fsid} cookie, never an
 * {@code HttpSession} — so servlet sessions are disabled and CSRF is off. CSRF protection defends a
 * cookie-authenticated <em>browser form post</em>; here the cookie carries no authority to act, only
 * a visitor id, and the admin surface is HTTP Basic.
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // /actuator/health is the container healthcheck and must stay open. The rest
                        // must not: `metrics` and `prometheus` describe inventory levels, queue
                        // depth, order rates and connection-pool pressure — a live read on how the
                        // sale is going, and a useful one to anyone attacking it.
                        .requestMatchers("/actuator/health/**", "/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .httpBasic(basic -> {})
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    /**
     * A single in-memory operator. Deliberately trivial: the MVP has no admin console and no user
     * store, and a real deployment would replace this bean rather than extend it.
     */
    @Bean
    public UserDetailsService adminUser(
            @Value("${flashseats.admin.username:admin}") String username,
            @Value("${flashseats.admin.password:admin}") String password) {
        return new InMemoryUserDetailsManager(
                User.withUsername(username).password("{noop}" + password).roles("ADMIN").build());
    }
}
