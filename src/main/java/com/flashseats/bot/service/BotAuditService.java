package com.flashseats.bot.service;

import com.flashseats.bot.model.BotAuditLog;
import com.flashseats.bot.model.BotOutcome;
import com.flashseats.bot.repository.BotAuditLogRepository;
import jakarta.annotation.PreDestroy;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records refusals, asynchronously and at best effort.
 *
 * <p><strong>Asynchronous is the whole design, not an optimisation.</strong> Every row written here
 * is written on a path that has just refused someone, and the caller is very often an attacker. A
 * synchronous insert would let them convert their own {@code 429}s into database writes at whatever
 * rate they can generate requests — turning the rate limiter into an amplifier for the load it
 * exists to shed, and doing it during the flash sale, against the pool checkout needs.
 *
 * <p><strong>Bounded, and it drops rather than blocks.</strong> {@code DiscardPolicy} on a queue of
 * 1,000: if the audit trail cannot keep up, the right outcome is to lose audit rows, never to make
 * the request path wait on them. An audit log is evidence, and evidence is not worth an outage.
 *
 * <p>Nothing here is on the allowed path. There is no {@code ALLOWED} outcome and there must not be
 * one — that would be a write per request during exactly the traffic this system is built for.
 */
@Slf4j
@Service
public class BotAuditService {

    private static final int QUEUE_DEPTH = 1_000;

    private final BotAuditLogRepository logs;
    private final ThreadPoolExecutor writer;

    public BotAuditService(BotAuditLogRepository logs) {
        this.logs = logs;
        this.writer = new ThreadPoolExecutor(
                1,
                1,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(QUEUE_DEPTH),
                runnable -> Thread.ofVirtual().name("bot-audit").unstarted(runnable),
                new ThreadPoolExecutor.DiscardPolicy());
    }

    public void record(
            String sessionId, String ipAddress, String path, BotOutcome outcome, String detail) {

        writer.execute(() -> {
            try {
                // save() carries its own transaction. A @Transactional method on this class would
                // not: it would be called through `this` from the lambda above, which Spring's proxy
                // does not intercept, so it would run with no transaction at all and look fine.
                logs.save(new BotAuditLog(sessionId, ipAddress, truncate(path), outcome, truncate(detail)));
            } catch (RuntimeException failed) {
                // Never propagate: this thread exists to observe the request, not to affect it.
                log.warn("Could not write a bot audit row ({})", outcome, failed);
            }
        });
    }

    /**
     * Stops the writer on shutdown, without waiting on it.
     *
     * <p>{@code shutdownNow} rather than a drain: the queued rows describe requests that have
     * already been refused, and holding a shutdown open for them would delay a rolling deploy to
     * preserve evidence about traffic that was turned away. Same trade as the discard policy.
     */
    @PreDestroy
    void stop() {
        writer.shutdownNow();
    }

    @Transactional(readOnly = true)
    public Page<BotAuditLog> recent(int page, int size) {
        return logs.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size));
    }

    /** The columns are 255; a path or a provider message longer than that is truncated, not refused. */
    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 255 ? value : value.substring(0, 255);
    }
}
