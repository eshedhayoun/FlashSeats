package com.flashseats.bot.service;

import com.flashseats.bot.exception.BotErrors;
import com.flashseats.bot.facade.BotFacade;
import com.flashseats.bot.model.BotOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * The challenge check {@code queue} runs on join, and the audit row each verdict earns. It
 * implements {@link BotFacade} (ADR-057) and orchestrates the verdict (global standards §5).
 */
@Slf4j
@Service
public class BotVerificationService implements BotFacade {

    private static final String JOIN_PATH = "/api/v1/queue/join";

    private final RecaptchaService recaptcha;
    private final BotAuditService audit;
    private final BotMetrics metrics;

    public BotVerificationService(
            RecaptchaService recaptcha,
            BotAuditService audit,
            BotMetrics metrics) {
        this.recaptcha = recaptcha;
        this.audit = audit;
        this.metrics = metrics;
    }

    @Override
    public void verifyHuman(String sessionId, String recaptchaToken, String ipAddress) {
        RecaptchaService.Verdict verdict = recaptcha.verify(sessionId, recaptchaToken);

        switch (verdict) {
            case FAILED -> {
                metrics.recordRefusal(BotOutcome.VERIFICATION_FAILED);
                audit.record(
                        sessionId,
                        ipAddress,
                        JOIN_PATH,
                        BotOutcome.VERIFICATION_FAILED,
                        null);
                throw BotErrors.verificationFailed();
            }
            // Audited precisely because it is allowed. Failing open is invisible from the outside,
            // and "our bot defence was off for three hours" must not be learned from an absence.
            case DEGRADED -> audit.record(
                    sessionId, ipAddress, JOIN_PATH, BotOutcome.VERIFICATION_DEGRADED,
                    "challenge provider unavailable or token missing");
            // PASSED and DISABLED write nothing. An audit row per allowed request would be a write
            // per join during exactly the traffic this system exists to serve.
            case PASSED, DISABLED -> { }
        }
    }
}
