package com.flashseats.bot.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashseats.bot.model.IpRuleAction;
import com.flashseats.bot.service.BotAuditService;
import com.flashseats.bot.service.IpRuleService;
import com.flashseats.bot.service.RateLimitService;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;

class RateLimitFilterTest {

    @Test
    void sseStreamIsCountedOnceAtConnection() throws Exception {
        RateLimitService rateLimits = mock(RateLimitService.class);
        IpRuleService ipRules = mock(IpRuleService.class);
        BotAuditService audit = mock(BotAuditService.class);
        ObjectMapper json = mock(ObjectMapper.class);
        FilterChain chain = mock(FilterChain.class);

        when(rateLimits.isTrustedProxy("127.0.0.1")).thenReturn(false);
        when(ipRules.actionFor("127.0.0.1")).thenReturn(null);
        when(rateLimits.allowSession("session-1")).thenReturn(true);
        when(rateLimits.allowIp("127.0.0.1")).thenReturn(true);

        RateLimitFilter filter =
                new RateLimitFilter(rateLimits, ipRules, audit, json);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/queue/stream");
        request.setRemoteAddr("127.0.0.1");
        request.setAttribute(
                com.flashseats.shared.identity.SessionId.REQUEST_ATTRIBUTE,
                "session-1");

        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        // The SSE connection itself is charged.
        verify(rateLimits).allowSession("session-1");
        verify(rateLimits).allowIp("127.0.0.1");

        // The request is allowed through after the one charge.
        verify(chain).doFilter(request, response);
    }
    @Test
    void trustedProxyProvidesTheClientAddress() throws Exception {
        RateLimitService rateLimits = mock(RateLimitService.class);
        IpRuleService ipRules = mock(IpRuleService.class);
        BotAuditService audit = mock(BotAuditService.class);
        ObjectMapper json = mock(ObjectMapper.class);
        FilterChain chain = mock(FilterChain.class);

        when(rateLimits.isTrustedProxy("10.0.0.10")).thenReturn(true);
        when(ipRules.actionFor("203.0.113.55")).thenReturn(null);
        when(rateLimits.allowSession("session-1")).thenReturn(true);
        when(rateLimits.allowIp("203.0.113.55")).thenReturn(true);

        RateLimitFilter filter =new RateLimitFilter(rateLimits, ipRules, audit, json);

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI("/api/v1/queue/stream");
        request.setRemoteAddr("10.0.0.10");
        request.addHeader("X-Forwarded-For", "203.0.113.55");

        request.setAttribute(com.flashseats.shared.identity.SessionId.REQUEST_ATTRIBUTE,"session-1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, chain);

        // The proxy itself is trusted.
        verify(rateLimits).isTrustedProxy("10.0.0.10");
        // The forwarded client address, not the proxy address, drives the rule lookup.
        verify(ipRules).actionFor("203.0.113.55");

        // The same resolved client address drives the IP bucket.
        verify(rateLimits).allowIp("203.0.113.55");

        // Downstream code receives the already-resolved address.
        assertThat(
                request.getAttribute(
                        com.flashseats.shared.web.ClientAddress.REQUEST_ATTRIBUTE))
                .isEqualTo("203.0.113.55");

        verify(chain).doFilter(request, response);
    }
    @Test
    void trustedProxyUsesTheFirstForwardedAddress() throws Exception {
        RateLimitService rateLimits = mock(RateLimitService.class);
        IpRuleService ipRules = mock(IpRuleService.class);
        BotAuditService audit = mock(BotAuditService.class);
        ObjectMapper json = mock(ObjectMapper.class);
        FilterChain chain = mock(FilterChain.class);

        when(rateLimits.isTrustedProxy("10.0.0.10")).thenReturn(true);
        when(ipRules.actionFor("203.0.113.55")).thenReturn(null);
        when(rateLimits.allowSession("session-1")).thenReturn(true);
        when(rateLimits.allowIp("203.0.113.55")).thenReturn(true);

        RateLimitFilter filter =
                new RateLimitFilter(rateLimits, ipRules, audit, json);

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

        assertThat(request.getAttribute(
                com.flashseats.shared.web.ClientAddress.REQUEST_ATTRIBUTE))
                .isEqualTo("203.0.113.55");

        verify(chain).doFilter(request, response);
        }
}