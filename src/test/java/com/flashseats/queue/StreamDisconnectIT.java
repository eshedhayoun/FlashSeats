package com.flashseats.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flashseats.app.support.IntegrationTest;
import com.flashseats.app.support.SaleFixture;
import com.flashseats.queue.service.QueueBroadcaster;
import com.flashseats.queue.service.SseEmitterRegistry;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * A buyer closing the waiting-room tab is the most common event in a sale. It must cost a debug line,
 * not an {@code ERROR} with stack traces: at ten thousand waiting buyers the second is a log flood that
 * buries every real failure (ADR-077).
 */
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("A closed waiting-room tab is routine, not an error")
class StreamDisconnectIT extends IntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private QueueBroadcaster broadcaster;

    @Autowired
    private SseEmitterRegistry registry;

    @Test
    void aClosedStreamLogsNoError(CapturedOutput output) throws Exception {
        fixture.reset();
        long eventId = fixture.openEvent("Closed Tab");
        fixture.tierWithoutCounter(eventId, "General Admission", 2_500, 10);

        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient http = HttpClient.newBuilder().cookieHandler(cookies).build();
        http.send(request("/events/" + eventId).GET().build(), HttpResponse.BodyHandlers.discarding());
        http.send(
                request("/queue/join")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"eventId\":" + eventId + "}"))
                        .build(),
                HttpResponse.BodyHandlers.discarding());
        HttpCookie session = cookies.getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("fsid"))
                .findFirst()
                .orElseThrow();

        // A raw socket, so the close is a real TCP close, exactly as a browser tab's.
        try (Socket socket = new Socket("localhost", port)) {
            OutputStream out = socket.getOutputStream();
            out.write(("GET /api/v1/queue/stream?eventId=" + eventId + " HTTP/1.1\r\n"
                            + "Host: localhost\r\n"
                            + "Accept: text/event-stream\r\n"
                            + "Cookie: fsid=" + session.getValue() + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = in.readLine()) != null && !line.contains("connected")) {
                // headers, then the ":connected" comment
            }
        }

        // The next frames find the socket gone; keep writing until the registry lets go of it.
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {
            broadcaster.pushHeartbeats();
            assertThat(registry.watchedEventIds()).doesNotContain(eventId);
        });
        Thread.sleep(500); // the container's own error notification is asynchronous

        assertThat(output.getAll())
                .doesNotContain("Unhandled exception")
                .doesNotContain("Failure in @ExceptionHandler");
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1" + path))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10));
    }
}
