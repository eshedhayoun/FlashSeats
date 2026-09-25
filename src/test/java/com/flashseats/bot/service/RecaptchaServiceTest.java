package com.flashseats.bot.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.flashseats.bot.config.BotProperties;
import java.time.Duration;
import java.net.URI;
import java.net.SocketTimeoutException;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.mockito.Mockito.times;
import com.flashseats.bot.exception.RecaptchaTransportException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;

import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;

class RecaptchaServiceTest {

    private static final String VERIFY_URL = "https://captcha.test/verify";

    @Test
    void acceptsSuccessfulVerificationAndCachesTheSession() {
        BotProperties properties = properties();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        org.mockito.Mockito.when(redis.opsForValue()).thenReturn(values);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(once(), requestTo(VERIFY_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess("{\"success\":true,\"score\":0.9}", MediaType.APPLICATION_JSON));

        RecaptchaService service = new RecaptchaService(properties, redis, builder.build());

        assertThat(service.verify("session-1", "token")).isEqualTo(RecaptchaService.Verdict.PASSED);

        verify(values).set("bot:verified:session-1", "1", Duration.ofSeconds(1800));
        server.verify();
    }
    @Test
    void cachedVerificationSkipsTheProviderOnTheNextRequest() {
        BotProperties properties = properties();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);

        org.mockito.Mockito.when(redis.opsForValue()).thenReturn(values);
        org.mockito.Mockito.when(redis.hasKey("bot:verified:session-1")).thenReturn(false, true);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(once(), requestTo(VERIFY_URL)).andExpect(method(HttpMethod.POST)).andRespond(withSuccess("{\"success\":true,\"score\":0.9}",MediaType.APPLICATION_JSON));

        RecaptchaService service = new RecaptchaService(properties,redis,builder.build());

        assertThat(service.verify("session-1", "token")).isEqualTo(RecaptchaService.Verdict.PASSED);

        assertThat(service.verify("session-1", "token")).isEqualTo(RecaptchaService.Verdict.PASSED);

        verify(redis, times(2)).hasKey("bot:verified:session-1");
        verify(values).set("bot:verified:session-1","1",Duration.ofSeconds(1800));

        // The MockRestServiceServer has exactly one expected provider call.
        // If the second verify() contacted reCAPTCHA, this assertion would fail.
        server.verify();
    }
    @Test
    void redisFailureDoesNotPreventSuccessfulVerification() {
        BotProperties properties = properties();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        org.mockito.Mockito.when(redis.hasKey("bot:verified:session-1"))
                .thenThrow(new RuntimeException("redis unavailable"));

        org.mockito.Mockito.when(redis.opsForValue())
                .thenThrow(new RuntimeException("redis unavailable"));

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        server.expect(once(), requestTo(VERIFY_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"success\":true,\"score\":0.9}",
                        MediaType.APPLICATION_JSON));

        RecaptchaService service = new RecaptchaService(
                properties,
                redis,
                builder.build());

        assertThat(service.verify("session-1", "token"))
                .isEqualTo(RecaptchaService.Verdict.PASSED);

        // Redis was unavailable for both the read and the cache write,
        // but the provider verification still succeeded and the request passed.
        server.verify();
    }

    @Test
    void rejectsVerificationBelowTheConfiguredScore() {
        BotProperties properties = properties();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(VERIFY_URL))
                .andRespond(withSuccess("{\"success\":true,\"score\":0.2}", MediaType.APPLICATION_JSON));

        RecaptchaService service = new RecaptchaService(properties, redis, builder.build());

        assertThat(service.verify("session-1", "token")).isEqualTo(RecaptchaService.Verdict.FAILED);

        server.verify();
    }

    @Test
    void failsOpenWhenVerificationTimesOut() {
        BotProperties properties = properties();
        properties.getRecaptcha().setReadTimeoutMs(20);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RestClient http = RestClient.builder()
                .requestFactory((ClientHttpRequestFactory) (uri, method) -> new TimeoutRequest(uri, method))
                .build();
        RecaptchaService service = new RecaptchaService(properties, redis, http);

        assertThat(service.verify("session-1", "token")).isEqualTo(RecaptchaService.Verdict.DEGRADED);
    }

    @Test
    void failsOpenWhenProviderReturnsAnUnusableResponse() {
        BotProperties properties = properties();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo(VERIFY_URL))
                .andRespond(withSuccess("{\"success\":false}", MediaType.APPLICATION_JSON));

        RecaptchaService service = new RecaptchaService(properties, redis, builder.build());

        assertThat(service.verify("session-1", "token")).isEqualTo(RecaptchaService.Verdict.DEGRADED);

        server.verify();
    }
    @Test
    void retriesTransportFailureOnceAndThenSucceeds() {
        BotProperties properties = properties();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        // First provider call fails with 500.
        server.expect(once(), requestTo(VERIFY_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        // Retry succeeds.
        server.expect(once(), requestTo(VERIFY_URL))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withSuccess(
                        "{\"success\":true,\"score\":0.9}",
                        MediaType.APPLICATION_JSON));

        CircuitBreaker circuitBreaker =
                CircuitBreaker.ofDefaults("recaptcha-retry-test");

        Retry retry = Retry.of(
                "recaptcha-retry-test",
                RetryConfig.custom()
                        .maxAttempts(2)
                        .waitDuration(Duration.ZERO)
                        .retryExceptions(RecaptchaTransportException.class)
                        .build());

        RecaptchaService service = new RecaptchaService(
                properties,
                redis,
                builder.build(),
                circuitBreaker,
                retry);

        assertThat(service.verify("session-1", "token"))
                .isEqualTo(RecaptchaService.Verdict.PASSED);

        server.verify();
    }

    @Test
    void failsOpenImmediatelyWhenCircuitIsOpen() {
        BotProperties properties = properties();
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

        CircuitBreaker circuitBreaker =
                CircuitBreaker.ofDefaults("recaptcha-open-test");
        circuitBreaker.transitionToOpenState();

        Retry retry = Retry.of(
                "recaptcha-open-test",
                RetryConfig.custom()
                        .maxAttempts(2)
                        .waitDuration(Duration.ZERO)
                        .retryExceptions(RecaptchaTransportException.class)
                        .build());

        RecaptchaService service = new RecaptchaService(
                properties,
                redis,
                builder.build(),
                circuitBreaker,
                retry);

        assertThat(service.verify("session-1", "token"))
                .isEqualTo(RecaptchaService.Verdict.DEGRADED);

        // There are deliberately no server expectations.
        // Any provider call would make MockRestServiceServer fail the test.
        server.verify();
    }
    private static BotProperties properties() {
        BotProperties properties = new BotProperties();
        properties.getRecaptcha().setSecret("test-secret");
        properties.getRecaptcha().setVerifyUrl(VERIFY_URL);
        return properties;
    }

    private static final class TimeoutRequest implements ClientHttpRequest {

        private final URI uri;
        private final HttpMethod method;

        private TimeoutRequest(URI uri, HttpMethod method) {
            this.uri = uri;
            this.method = method;
        }

        @Override
        public URI getURI() {
            return uri;
        }

        @Override
        public HttpMethod getMethod() {
            return method;
        }

        @Override
        public org.springframework.http.HttpHeaders getHeaders() {
            return new org.springframework.http.HttpHeaders();
        }

        @Override
        public java.io.OutputStream getBody() {
            return java.io.OutputStream.nullOutputStream();
        }

        @Override
        public org.springframework.http.client.ClientHttpResponse execute() {
            throw new ResourceAccessException("captcha request timed out", new SocketTimeoutException("timeout"));
        }

        @Override
        public java.util.Map<String, Object> getAttributes() {
            return java.util.Map.of();
        }
    }
}
