package com.flashseats.shared.error;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A connection-pool timeout is back-pressure, and says so (ADR-059).
 *
 * <p>Before this, HikariCP's timeout fell through to the {@code Exception} backstop and a buyer
 * mid-checkout was told {@code 500 INTERNAL_ERROR} — the least actionable code in the registry, for
 * a request that would very likely succeed a second later. 1,218 of them in the 2,000-VU run.
 *
 * <p>Standalone {@code MockMvc}, not an integration test: exhausting a real pool on demand is slow
 * and flaky, and the only thing under test is how the handler classifies an exception it is given.
 */
@DisplayName("A pool timeout is 503 SERVICE_BUSY, not 500")
class BackPressureResponseTest {

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Thrower())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    @Test
    @DisplayName("A transaction that could not get a connection is retryable back-pressure")
    void poolTimeoutAtTransactionStart() throws Exception {
        mvc.perform(get("/tx-timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "1"))
                .andExpect(jsonPath("$.code").value("SERVICE_BUSY"))
                .andExpect(jsonPath("$.retryable").value(true))
                .andExpect(jsonPath("$.retryAfterSeconds").value(1));
    }

    @Test
    @DisplayName("The same timeout reached through plain JDBC is classified the same way")
    void poolTimeoutThroughJdbc() throws Exception {
        mvc.perform(get("/jdbc-timeout"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SERVICE_BUSY"));
    }

    @Test
    @DisplayName("A transaction failure that is NOT a pool timeout stays a 500")
    void otherTransactionFailuresAreStillFaults() throws Exception {
        // Reclassifying every CannotCreateTransactionException would tell a client to retry a
        // database that is down, or misconfigured — neither is going to be better in a second.
        mvc.perform(get("/tx-other"))
                .andExpect(status().isInternalServerError())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"));
    }

    @RestController
    static class Thrower {

        @GetMapping("/tx-timeout")
        void txTimeout() {
            throw new CannotCreateTransactionException(
                    "Could not open JPA EntityManager for transaction", hikariTimeout());
        }

        @GetMapping("/jdbc-timeout")
        void jdbcTimeout() {
            throw new CannotGetJdbcConnectionException("Failed to obtain JDBC Connection", hikariTimeout());
        }

        @GetMapping("/tx-other")
        void txOther() {
            throw new CannotCreateTransactionException(
                    "Could not open JPA EntityManager for transaction",
                    new IllegalStateException("database is not accepting connections"));
        }

        /** What HikariCP actually throws when {@code connection-timeout} elapses. */
        private static SQLTransientConnectionException hikariTimeout() {
            return new SQLTransientConnectionException(
                    "HikariPool-1 - Connection is not available, request timed out after 3000ms "
                            + "(total=30, active=30, idle=0, waiting=75)");
        }
    }
}
