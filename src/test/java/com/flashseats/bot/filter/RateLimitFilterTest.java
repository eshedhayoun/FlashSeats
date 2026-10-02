package com.flashseats.bot.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashseats.bot.model.IpRuleAction;
import com.flashseats.bot.service.BotAuditService;
import com.flashseats.bot.service.BotMetrics;
import com.flashseats.bot.service.IpRuleService;
import com.flashseats.bot.service.RateLimitService;
import com.flashseats.shared.identity.SessionId;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class RateLimitFilterTest {

    @Test
    void sseStreamIsCountedOnceAtConnection() throws Exception {
        RateLimitService rateLimits = mock(RateLimitService.class);
        IpRuleService ipRules = mock(IpRuleService.class);
        BotAuditService audit = mock(BotAuditService.class);
        BotMetrics metrics = mock(BotMetrics.class);
        ObjectMapper json = mock(ObjectMapper.class);
        FilterChain chain = mock(FilterChain.class);

        when(rateLimits.isTrustedProxy("127.0.0.1")).thenReturn(false);
        when(ipRules.actionFor("127.0.0.1")).thenReturn(null);
        when(rateLimits.allowSession("session-1")).thenReturn(true);
        when(rateLimits.allowIp("127.0.0.1")).thenReturn(true);

        RateLimitFilter filter =
                new RateLimitFilter(rateLimits, ipRules, audit, metrics, json);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/queue/stream");
        request.setRemoteAddr("127.0.0.1");
        request.setAttribute(
                com.flashseats.shared.identity.SessionId.REQUEST_ATTRIBUTE,
                "session-1");

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(rateLimits).allowSession("session-1");
        verify(rateLimits).allowIp("127.0.0.1");
        verify(chain).doFilter(request, response);
    }

    @Test
    void trustedProxyProvidesTheClientAddress() throws Exception {
        RateLimitService rateLimits = mock(RateLimitService.class);
        IpRuleService ipRules = mock(IpRuleService.class);
        BotAuditService audit = mock(BotAuditService.class);
        BotMetrics metrics = mock(BotMetrics.class);
        ObjectMapper json = mock(ObjectMapper.class);
        FilterChain chain = mock(FilterChain.class);

        when(rateLimits.isTrustedProxy("10.0.0.10")).thenReturn(true);
        when(ipRules.actionFor("203.0.113.55")).thenReturn(null);
        when(rateLimits.allowSession("session-1")).thenReturn(true);
        when(rateLimits.allowIp("203.0.113.55")).thenReturn(true);

        RateLimitFilter filter =
                new RateLimitFilter(rateLimits, ipRules, audit, metrics, json);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/queue/stream");
        request.setRemoteAddr("10.0.0.10");
        request.addHeader("X-Forwarded-For", "203.0.113.55");
        request.setAttribute(
                com.flashseats.shared.identity.SessionId.REQUEST_ATTRIBUTE,
                "session-1");

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(rateLimits).isTrustedProxy("10.0.0.10");
        verify(ipRules).actionFor("203.0.113.55");
        verify(rateLimits).allowIp("203.0.113.55");

        assertThat(
                request.getAttribute(
                        com.flashseats.shared.web.ClientAddress.REQUEST_ATTRIBUTE))
                .isEqualTo("203.0.113.55");

        verify(chain).doFilter(request, response);
    }

    @Test
    void aChainOfTrustedProxiesResolvesToTheClient() throws Exception {
        RateLimitService rateLimits = mock(RateLimitService.class);
        IpRuleService ipRules = mock(IpRuleService.class);
        BotAuditService audit = mock(BotAuditService.class);
        BotMetrics metrics = mock(BotMetrics.class);
        ObjectMapper json = mock(ObjectMapper.class);
        FilterChain chain = mock(FilterChain.class);

        // Every hop after the client is a proxy we run; the client is the right-most entry that is not.
        when(rateLimits.isTrustedProxy("10.0.0.10")).thenReturn(true);
        when(rateLimits.isTrustedProxy("10.0.0.11")).thenReturn(true);
        when(rateLimits.isTrustedProxy("10.0.0.12")).thenReturn(true);
        when(ipRules.actionFor("203.0.113.55")).thenReturn(null);
        when(rateLimits.allowSession("session-1")).thenReturn(true);
        when(rateLimits.allowIp("203.0.113.55")).thenReturn(true);

        RateLimitFilter filter =
                new RateLimitFilter(rateLimits, ipRules, audit, metrics, json);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/queue/stream");
        request.setRemoteAddr("10.0.0.10");
        request.addHeader(
                "X-Forwarded-For",
                "203.0.113.55, 10.0.0.11, 10.0.0.12");
        request.setAttribute(
                com.flashseats.shared.identity.SessionId.REQUEST_ATTRIBUTE,
                "session-1");

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(ipRules).actionFor("203.0.113.55");
        verify(rateLimits).allowIp("203.0.113.55");

        assertThat(
                request.getAttribute(
                        com.flashseats.shared.web.ClientAddress.REQUEST_ATTRIBUTE))
                .isEqualTo("203.0.113.55");

        verify(chain).doFilter(request, response);
    }

    @Test
    void ipDenyRecordsRefusalMetric() throws Exception {
        RateLimitService rateLimits = mock(RateLimitService.class);
        IpRuleService ipRules = mock(IpRuleService.class);
        BotAuditService audit = mock(BotAuditService.class);
        BotMetrics metrics = mock(BotMetrics.class);
        ObjectMapper json = mock(ObjectMapper.class);
        FilterChain chain = mock(FilterChain.class);

        when(rateLimits.isTrustedProxy("127.0.0.1")).thenReturn(false);
        when(ipRules.actionFor("127.0.0.1")).thenReturn(IpRuleAction.DENY);

        RateLimitFilter filter =
                new RateLimitFilter(rateLimits, ipRules, audit, metrics, json);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/queue/join");
        request.setRemoteAddr("127.0.0.1");

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(metrics).recordRefusal(
                com.flashseats.bot.model.BotOutcome.IP_BLOCKED);
    }

    @Test
    void rateLimitRecordsRefusalMetric() throws Exception {
        RateLimitService rateLimits = mock(RateLimitService.class);
        IpRuleService ipRules = mock(IpRuleService.class);
        BotAuditService audit = mock(BotAuditService.class);
        BotMetrics metrics = mock(BotMetrics.class);
        ObjectMapper json = mock(ObjectMapper.class);
        FilterChain chain = mock(FilterChain.class);

        when(rateLimits.isTrustedProxy("127.0.0.1")).thenReturn(false);
        when(ipRules.actionFor("127.0.0.1")).thenReturn(null);
        when(rateLimits.allowSession("session-1")).thenReturn(false);

        RateLimitFilter filter =
                new RateLimitFilter(rateLimits, ipRules, audit, metrics, json);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/queue/join");
        request.setRemoteAddr("127.0.0.1");
        request.setAttribute(
                com.flashseats.shared.identity.SessionId.REQUEST_ATTRIBUTE,
                "session-1");

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(metrics).recordRefusal(
                com.flashseats.bot.model.BotOutcome.RATE_LIMITED);
    }

    /**
     * The buckets live in Redis. Unreachable, the filter threw below every exception handler and every
     * API call answered a bare 500 with no code. It fails open now, counted (ADR-067).
     */
    @Test
    void anUnreadableLimiterLetsTheRequestThroughAndSaysSo() throws Exception {
        RateLimitService rateLimits = mock(RateLimitService.class);
        IpRuleService ipRules = mock(IpRuleService.class);
        BotMetrics metrics = mock(BotMetrics.class);
        FilterChain chain = mock(FilterChain.class);

        when(rateLimits.isTrustedProxy("127.0.0.1")).thenReturn(false);
        when(rateLimits.allowSession("session-1"))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        RateLimitFilter filter = new RateLimitFilter(
                rateLimits, ipRules, mock(BotAuditService.class), metrics, mock(ObjectMapper.class));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/events");
        request.setRemoteAddr("127.0.0.1");
        request.setAttribute(SessionId.REQUEST_ATTRIBUTE, "session-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verify(metrics).recordLimiterUnavailable();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    /**
     * Each proxy appends the address it saw, so everything left of the last trusted hop is whatever the
     * client sent. Reading the left-most entry let any caller mint a fresh IP bucket per request (ADR-071).
     */
    @Test
    void aSpoofedForwardedForBehindATrustedProxyIsIgnored() throws Exception {
        RateLimitService rateLimits = mock(RateLimitService.class);
        IpRuleService ipRules = mock(IpRuleService.class);
        FilterChain chain = mock(FilterChain.class);

        when(rateLimits.isTrustedProxy("172.28.0.10")).thenReturn(true);
        when(rateLimits.allowSession("session-1")).thenReturn(true);
        when(rateLimits.allowIp("203.0.113.9")).thenReturn(true);

        RateLimitFilter filter = new RateLimitFilter(
                rateLimits, ipRules, mock(BotAuditService.class), mock(BotMetrics.class), mock(ObjectMapper.class));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/queue/join");
        request.setRemoteAddr("172.28.0.10");
        // The client sent "6.6.6.6"; nginx appended the address it actually saw.
        request.addHeader("X-Forwarded-For", "6.6.6.6, 203.0.113.9");
        request.setAttribute(SessionId.REQUEST_ATTRIBUTE, "session-1");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(request.getAttribute(com.flashseats.shared.web.ClientAddress.REQUEST_ATTRIBUTE))
                .isEqualTo("203.0.113.9");
        verify(rateLimits).allowIp("203.0.113.9");
    }

    /** A trusted hop inside the chain — the load harness in front of nginx — is skipped too. */
    @Test
    void trustedHopsInsideTheChainAreSkipped() throws Exception {
        RateLimitService rateLimits = mock(RateLimitService.class);
        IpRuleService ipRules = mock(IpRuleService.class);
        FilterChain chain = mock(FilterChain.class);

        when(rateLimits.isTrustedProxy("172.28.0.10")).thenReturn(true);
        when(rateLimits.isTrustedProxy("172.28.0.200")).thenReturn(true);
        when(rateLimits.allowSession("session-1")).thenReturn(true);
        when(rateLimits.allowIp("10.0.3.7")).thenReturn(true);

        RateLimitFilter filter = new RateLimitFilter(
                rateLimits, ipRules, mock(BotAuditService.class), mock(BotMetrics.class), mock(ObjectMapper.class));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/queue/join");
        request.setRemoteAddr("172.28.0.10");
        request.addHeader("X-Forwarded-For", "10.0.3.7, 172.28.0.200");
        request.setAttribute(SessionId.REQUEST_ATTRIBUTE, "session-1");

        filter.doFilter(request, new MockHttpServletResponse(), chain);

        assertThat(request.getAttribute(com.flashseats.shared.web.ClientAddress.REQUEST_ATTRIBUTE))
                .isEqualTo("10.0.3.7");
    }
}
