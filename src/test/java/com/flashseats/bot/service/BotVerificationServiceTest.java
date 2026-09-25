package com.flashseats.bot.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flashseats.bot.exception.BotVerificationFailedException;
import com.flashseats.bot.model.BotOutcome;
import org.junit.jupiter.api.Test;

class BotVerificationServiceTest {

    @Test
    void lowScoreRefusesJoinAndWritesFailureAudit() {
        RecaptchaService recaptcha = org.mockito.Mockito.mock(RecaptchaService.class);
        BotAuditService audit = org.mockito.Mockito.mock(BotAuditService.class);
        BotMetrics metrics = org.mockito.Mockito.mock(BotMetrics.class);

        when(recaptcha.verify("session-1", "token"))
                .thenReturn(RecaptchaService.Verdict.FAILED);

        BotVerificationService service =
                new BotVerificationService(recaptcha, audit, metrics);

        assertThatThrownBy(() ->
                service.verifyHuman("session-1", "token", "203.0.113.10"))
                .isInstanceOf(BotVerificationFailedException.class);

        verify(metrics).recordRefusal(BotOutcome.VERIFICATION_FAILED);

        verify(audit).record(
                "session-1",
                "203.0.113.10",
                "/api/v1/queue/join",
                BotOutcome.VERIFICATION_FAILED,
                null);
    }

    @Test
    void providerUnavailableStillAllowsJoinAndWritesDegradedAudit() {
        RecaptchaService recaptcha = org.mockito.Mockito.mock(RecaptchaService.class);
        BotAuditService audit = org.mockito.Mockito.mock(BotAuditService.class);
        BotMetrics metrics = org.mockito.Mockito.mock(BotMetrics.class);

        when(recaptcha.verify("session-2", "token"))
                .thenReturn(RecaptchaService.Verdict.DEGRADED);

        BotVerificationService service =
                new BotVerificationService(recaptcha, audit, metrics);

        service.verifyHuman("session-2", "token", "203.0.113.11");

        verify(audit).record(
                "session-2",
                "203.0.113.11",
                "/api/v1/queue/join",
                BotOutcome.VERIFICATION_DEGRADED,
                "challenge provider unavailable or token missing");

        // DEGRADED is allowed traffic, so it must not increment the refusal metric.
        verifyNoInteractions(metrics);
    }

    @Test
    void passedVerificationDoesNotWriteAnAuditRowOrMetric() {
        RecaptchaService recaptcha = org.mockito.Mockito.mock(RecaptchaService.class);
        BotAuditService audit = org.mockito.Mockito.mock(BotAuditService.class);
        BotMetrics metrics = org.mockito.Mockito.mock(BotMetrics.class);

        when(recaptcha.verify("session-3", "token"))
                .thenReturn(RecaptchaService.Verdict.PASSED);

        BotVerificationService service =
                new BotVerificationService(recaptcha, audit, metrics);

        service.verifyHuman("session-3", "token", "203.0.113.12");

        verifyNoInteractions(audit, metrics);
    }
}