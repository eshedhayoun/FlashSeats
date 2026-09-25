package com.flashseats.app;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Application-wide HTTP security. Without an explicit chain, Boot would lock every endpoint behind a
 * generated password. {@code /api/v1/admin/**} and {@code /actuator/**} (except health) require
 * {@code ROLE_ADMIN}; everything else is open. Stateless: identity is the signed {@code fsid} cookie,
 * so servlet sessions and CSRF are off (§10 S6; ADR-060 closes the one CSRF path that mattered).
 */
@Configuration
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http, AdminProblemResponses problems)
            throws Exception {
        return http.authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // /actuator/health is the container healthcheck and must stay open. The rest
                        // must not: `metrics` and `prometheus` describe inventory levels, queue
                        // depth, order rates and connection-pool pressure — a live read on how the
                        // sale is going, and a useful one to anyone attacking it.
                        .requestMatchers("/actuator/health/**", "/actuator/health").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        .anyRequest().permitAll())
                .httpBasic(basic -> basic.authenticationEntryPoint(problems.entryPoint()))
                // Both are needed, and they are not the same case: the entry point answers "no
                // usable credentials", this answers "credentials, but not an operator's". Left to
                // Boot they were the only responses in the API with no registry `code`.
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(problems.entryPoint())
                        .accessDeniedHandler(problems.accessDeniedHandler()))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .build();
    }

    /**
     * A delegating encoder, so the stored value names its algorithm: {@code {bcrypt}...} anywhere real,
     * {@code {noop}admin} on {@code dev}/{@code test}. {@code SecretsGuard} refuses any {@code {noop}}
     * value outside those profiles (ADR-048).
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * The single operator account, stored bcrypt-hashed. It is in memory on purpose: one identity needs
     * no user-management surface. Replace this bean when a second operator appears (§10 S12).
     */
    @Bean
    public UserDetailsService adminUser(
            @Value("${flashseats.admin.username:admin}") String username,
            @Value("${flashseats.admin.password:{noop}admin}") String encodedPassword) {
        return new InMemoryUserDetailsManager(
                User.withUsername(username).password(encodedPassword).roles("ADMIN").build());
    }
}
