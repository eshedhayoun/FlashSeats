package com.flashseats.flashseats.support;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * One buyer, driving the real HTTP API with their own cookie jar.
 *
 * <p>A {@link CookieManager} rather than a hand-managed header, because the {@code fsid} cookie is
 * the <em>only</em> identity this system accepts. A test that dropped it would create a brand-new
 * visitor on every call and could never reproduce a real journey — and would silently pass while
 * proving nothing about session-scoped behaviour.
 *
 * <p>Uses the JDK client so the journey is exercised over genuine HTTP, status codes and all.
 */
public class BuyerSession {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final String baseUrl;

    public BuyerSession(int port) {
        this.http = HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        this.baseUrl = "http://localhost:" + port + "/api/v1";
    }

    public Response get(String path) {
        return send(request(path).GET(), Map.of());
    }

    public Response post(String path, Object body) {
        return post(path, body, Map.of());
    }

    public Response post(String path, Object body, Map<String, String> headers) {
        return send(request(path).POST(publisher(body)), headers);
    }

    public Response delete(String path) {
        return send(request(path).DELETE(), Map.of());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json")
                .header("Accept", "application/json, application/problem+json")
                .timeout(Duration.ofSeconds(20));
    }

    private HttpRequest.BodyPublisher publisher(Object body) {
        if (body == null) {
            return HttpRequest.BodyPublishers.noBody();
        }
        try {
            return HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body));
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialise request body", e);
        }
    }

    private Response send(HttpRequest.Builder builder, Map<String, String> headers) {
        headers.forEach(builder::header);
        try {
            HttpResponse<String> response =
                    http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            return new Response(response.statusCode(), response.body());
        } catch (Exception e) {
            throw new IllegalStateException("Request failed", e);
        }
    }

    /** A status code and a parsed body — everything an assertion about this API needs. */
    public record Response(int status, String rawBody) {

        public JsonNode json() {
            try {
                return JSON.readTree(rawBody);
            } catch (Exception e) {
                throw new IllegalStateException("Response was not JSON: " + rawBody, e);
            }
        }

        public String text(String field) {
            JsonNode value = json().get(field);
            return value == null || value.isNull() ? null : value.asText();
        }

        public int number(String field) {
            return json().get(field).asInt();
        }

        /** The stable machine-readable identifier every error carries. */
        public String errorCode() {
            return text("code");
        }

        public boolean ok() {
            return status >= 200 && status < 300;
        }
    }
}
