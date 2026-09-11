package com.flashseats.flashseats.config;

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
     * How {@code flashseats.admin.password} is read.
     *
     * <p>A <strong>delegating</strong> encoder, so the stored value names its own algorithm:
     * {@code {bcrypt}$2a$...} anywhere real, {@code {noop}admin} in {@code dev} and {@code test}
     * where the whole point is that a clean checkout runs with no configuration. The prefix is what
     * lets the two coexist without a second property or a profile branch, and what lets the
     * algorithm be upgraded later without touching this class.
     *
     * <p>{@code SecretsGuard} is the other half: outside {@code dev}/{@code test} it refuses to
     * start on any {@code {noop}} value at all, so a plaintext password cannot reach a deployment by
     * being different from the one string somebody thought to check for.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    /**
     * The single operator account.
     *
     * <p><strong>Still in memory, and that is a decision rather than an omission.</strong> There is
     * one operator identity and no requirement for a second; a table, a migration and a
     * user-management surface to administer one row would be machinery guarding nothing. §10's S12
     * asks for this bean to be replaced <em>"before anyone else needs access"</em>, and the moment a
     * second operator or an audit trail of who paused a sale is wanted, that is what changes — this
     * bean, and nothing around it, which is the property worth keeping.
     *
     * <p>What was genuinely wrong, and is fixed, is that the credential used to be stored and
     * compared in plaintext.
     */
    @Bean
    public UserDetailsService adminUser(
            @Value("${flashseats.admin.username:admin}") String username,
            @Value("${flashseats.admin.password:{noop}admin}") String encodedPassword) {
        return new InMemoryUserDetailsManager(
                User.withUsername(username).password(encodedPassword).roles("ADMIN").build());
    }
}
