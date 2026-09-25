package com.flashseats.shared.identity;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.app.support.IntegrationTest;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * {@code POST /session/reset} cannot be triggered by a cross-site form (ADR-060, §10 S13).
 *
 * <p>The endpoint expires the {@code fsid} cookie, and the session <em>is</em> the buyer's queue
 * position and their only authority over their hold. With CSRF disabled, a hidden form on any page
 * could throw a waiting buyer out of the line. A form can only send {@code urlencoded},
 * {@code multipart} or {@code text/plain}, and a cross-origin {@code fetch} sending
 * {@code application/json} needs a preflight nothing here grants — so requiring JSON closes it.
 *
 * <p>A raw {@link HttpClient}, not {@code BuyerSession}: the assertion is about the
 * {@code Set-Cookie} header, which is the whole effect of this endpoint.
 */
@DisplayName("Session reset refuses anything a cross-site form can send")
class SessionResetIT extends IntegrationTest {

    @LocalServerPort
    private int port;

    private final HttpClient http = HttpClient.newHttpClient();

    @Test
    @DisplayName("A form-encoded POST is 415 and expires nothing")
    void formPostIsRefused() throws Exception {
        HttpResponse<String> response = post("application/x-www-form-urlencoded", "");

        assertThat(response.statusCode()).isEqualTo(415);
        assertThat(response.body()).contains("VALIDATION_FAILED");
        // The identity filter may mint a fresh fsid for a cookieless caller; what must not appear is
        // the EXPIRY, which is the attack.
        assertThat(setCookies(response)).noneMatch(cookie -> cookie.contains("Max-Age=0"));
    }

    @Test
    @DisplayName("A text/plain POST — the other form enctype — is refused too")
    void plainTextPostIsRefused() throws Exception {
        assertThat(post("text/plain", "").statusCode()).isEqualTo(415);
    }

    @Test
    @DisplayName("A JSON POST, as the demo page sends, still expires the cookie")
    void jsonPostExpiresTheCookie() throws Exception {
        // No body, exactly as static/index.html sends it: the header alone is what is checked.
        HttpResponse<String> response = post("application/json", "");

        assertThat(response.statusCode()).isEqualTo(204);
        assertThat(setCookies(response))
                .anyMatch(cookie -> cookie.startsWith("fsid=;") && cookie.contains("Max-Age=0"));
    }

    private HttpResponse<String> post(String contentType, String body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("http://localhost:" + port + "/api/v1/session/reset"))
                .header("Content-Type", contentType)
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static List<String> setCookies(HttpResponse<?> response) {
        return response.headers().allValues("Set-Cookie");
    }
}
