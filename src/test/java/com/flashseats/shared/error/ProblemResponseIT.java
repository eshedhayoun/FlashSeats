package com.flashseats.shared.error;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.app.support.BuyerSession;
import com.flashseats.app.support.IntegrationTest;
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

    @Test
    @DisplayName("An unauthenticated admin call is 401 ADMIN_AUTH_REQUIRED, and says how to authenticate")
    void adminRefusalsCarryACodeToo() {
        // Spring Security throws in the filter chain, which runs BEFORE DispatcherServlet — so no
        // @RestControllerAdvice can see it, and Boot's stock error body came back instead. These
        // were the only endpoints in the API answering without a `code`, on the surface that can
        // pause a live sale. AdminProblemResponses is what closes that.
        var response = new BuyerSession(port).post("/admin/events/1/prewarm", Map.of());

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.errorCode()).isEqualTo("ADMIN_AUTH_REQUIRED");
        assertThat(response.text("traceId")).isNotBlank();
    }

    /**
     * ADR-041's trap, one more time: with static resources served, an unknown path reaches Spring as
     * {@code NoResourceFoundException}, and the {@code Exception} backstop answered it {@code 500
     * INTERNAL_ERROR} with an {@code ERROR} log line — a typo reported as an outage (ADR-067).
     */
    @Test
    @DisplayName("An unknown path is 404 NOT_FOUND, not 500")
    void anUnknownPathIsNotAServerFault() {
        var response = new BuyerSession(port).get("/no-such-endpoint");

        assertThat(response.status()).isEqualTo(404);
        assertThat(response.errorCode()).isEqualTo("NOT_FOUND");
        assertThat(response.text("traceId")).isNotBlank();
    }

    /**
     * The limits are the columns the values land in. An idempotency key longer than its column used
     * to fail its insert on every retry as a 500 (ADR-067).
     */
    @Test
    @DisplayName("Oversized checkout input is 400 VALIDATION_FAILED, before anything is written")
    void oversizedInputIsAClientError() {
        var response = new BuyerSession(port).post(
                "/orders/checkout",
                Map.of(
                        "holdToken", "hld-1",
                        "userEmail", "a".repeat(250) + "@example.com",
                        "paymentMethodId", "pm_card_visa",
                        "idempotencyKey", "k".repeat(65)));

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.errorCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.json().get("violations").toString())
                .contains("idempotencyKey")
                .contains("userEmail");
    }
}
