package com.flashseats.queue.service;

import com.flashseats.queue.config.QueueProperties;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * One shared promotion allowance for the whole cluster (ADR-049). The per-event tick lock does not
 * stop five sales each sending a full batch into the same connection pool, which surfaces only as
 * p99 collapse.
 *
 * <p>The allowance is <strong>claimed, not divided</strong>, so a quiet sale's share goes to whoever
 * is promoting. It <strong>fails closed</strong>: no claim means no promotion this tick, and a late
 * promotion costs a second while an unbounded one is the defect.
 */
@Slf4j
@Component
public class GlobalPromotionBudget {

    private final StringRedisTemplate redis;
    private final RedisScript<Long> claimScript;
    private final QueueProperties properties;

    public GlobalPromotionBudget(
            StringRedisTemplate redis,
            RedisScript<Long> promotionBudgetScript,
            QueueProperties properties) {
        this.redis = redis;
        this.claimScript = promotionBudgetScript;
        this.properties = properties;
    }

    /**
     * Claims up to {@code requested} admissions from the current window.
     *
     * @return how many may be promoted now — never more than was asked for, and never more than the
     *     window has left. {@code 0} means promote nobody.
     */
    public long claim(long requested) {
        long budget = properties.getGlobalAdmissionBudgetPerTick();
        if (requested <= 0 || budget <= 0) {
            return 0;
        }

        try {
            Long granted = redis.execute(
                    claimScript,
                    List.of(QueueKeys.admissionBudget()),
                    String.valueOf(budget),
                    String.valueOf(requested),
                    String.valueOf(properties.getPromotionIntervalMs()));
            return granted == null ? 0 : granted;
        } catch (RuntimeException failed) {
            log.error("Could not claim the admission budget; promoting nobody this tick", failed);
            return 0;
        }
    }
}
