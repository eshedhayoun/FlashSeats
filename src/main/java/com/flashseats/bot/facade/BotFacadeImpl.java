package com.flashseats.bot.facade;

import com.flashseats.bot.exception.BotVerificationFailedException;
import com.flashseats.bot.model.BotOutcome;
import com.flashseats.bot.service.BotAuditService;
import com.flashseats.bot.service.RecaptchaService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Thin delegation to {@link RecaptchaService}, plus the audit row each verdict earns. */
@Slf4j
@Component
class BotFacadeImpl implements BotFacade {

    private static final String JOIN_PATH = "/api/v1/queue/join";

    private final RecaptchaService recaptcha;
    private final BotAuditService audit;

    BotFacadeImpl(RecaptchaService recaptcha, BotAuditService audit) {
        this.recaptcha = recaptcha;
        this.audit = audit;
    }

    @Override
    public void verifyHuman(String sessionId, String recaptchaToken, String ipAddress) {
        RecaptchaService.Verdict verdict = recaptcha.verify(sessionId, recaptchaToken);

        switch (verdict) {
            case FAILED -> {
                audit.record(sessionId, ipAddress, JOIN_PATH, BotOutcome.VERIFICATION_FAILED, null);
                throw new BotVerificationFailedException();
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
