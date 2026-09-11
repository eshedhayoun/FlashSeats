package com.flashseats.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * Every failure is RFC 7807 with a registry {@code code} (global standards §1–§2).
 *
 * <p>The interesting cases are the ones Spring MVC rejects <em>before</em> a handler runs.
 * {@code ExceptionHandlerExceptionResolver} runs ahead of {@code DefaultHandlerExceptionResolver},
 * so {@link GlobalExceptionHandler}'s {@code Exception.class} backstop matched them first and
 * answered a malformed query string with {@code 500 INTERNAL_ERROR} — a client mistake reported as a
 * server fault, with no {@code code} for the SPA to branch on and an {@code ERROR} log line each
 * time. The client's checkout switch fell through to a default that re-enabled the Pay button.
 */
@DisplayName("Every error is a ProblemDetail carrying a registry code")
class ProblemResponseIT extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("A missing query parameter is 400 VALIDATION_FAILED, not 500")
    void missingParameterIsAClientError() {
        var response = new BuyerSession(port).get("/queue/status");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.text("traceId")).isNotBlank();
    }

    @Test
    @DisplayName("A path variable of the wrong type is 400, not 500")
    void badPathVariableIsAClientError() {
        var response = new BuyerSession(port).get("/events/not-a-number");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.errorCode()).isEqualTo("VALIDATION_FAILED");
    }

    @Test
    @DisplayName("A missing required header is 400, and names the header")
    void missingHeaderIsAClientError() {
        var response = new BuyerSession(port).post("/queue/admit", Map.of("eventId", 1));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.text("detail")).contains("X-Queue-Pass-Token");
    }

    @Test
    @DisplayName("A domain failure keeps its own status and code")
    void domainFailuresAreUnaffected() {
        var response = new BuyerSession(port).get("/events/999999");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.errorCode()).isEqualTo("EVENT_NOT_FOUND");
        assertThat(response.text("traceId")).isNotBlank();
    }
}
