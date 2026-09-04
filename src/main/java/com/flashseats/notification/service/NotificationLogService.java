package com.flashseats.notification.service;

import com.flashseats.notification.model.NotificationKind;
import com.flashseats.notification.model.NotificationLog;
import com.flashseats.notification.model.NotificationStatus;
import com.flashseats.notification.repository.NotificationLogRepository;
import java.time.Clock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The two <strong>short</strong> transactions that bracket rendering and sending.
 *
 * <p>Rendering a PDF is CPU-bound and SMTP is a network call; holding a pooled connection across
 * either would starve checkout, because under virtual threads the connection pool is the system's
 * real concurrency ceiling (ADR-023). So: claim, commit, do the slow work with nothing open, record
 * the outcome.
 */
@Service
public class NotificationLogService {

    private static final Logger log = LoggerFactory.getLogger(NotificationLogService.class);

    private final NotificationLogRepository logs;
    private final Clock clock;

    public NotificationLogService(NotificationLogRepository logs, Clock clock) {
        this.logs = logs;
        this.clock = clock;
    }

    /**
     * Claims the right to send this message, by inserting the row that makes a second attempt
     * impossible.
     *
     * <p><strong>Insert first, send second.</strong> The unique violation — not a preceding
     * {@code SELECT} — is the guard, because the constraint is atomic and a read is not. Two workers
     * handling the same redelivered message would both pass a {@code SELECT} and both send, and the
     * buyer would get two tickets (ADR-015).
     *
     * <p>{@code REQUIRES_NEW} so the claim commits on its own, independently of anything the caller
     * does afterwards.
     *
     * @return true if this caller may send; false if someone already has
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(String orderNumber, NotificationKind kind, String recipientEmail) {
        try {
            logs.saveAndFlush(new NotificationLog(orderNumber, kind, recipientEmail));
            return true;
        } catch (DataIntegrityViolationException alreadyHandled) {
            log.debug("{} for {} was already handled", kind, orderNumber);
            return false;
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(String orderNumber, NotificationKind kind) {
        logs.findByOrderNumberAndKind(orderNumber, kind).ifPresent(entry -> {
            entry.setStatus(NotificationStatus.SENT);
            entry.setSentAt(clock.instant());
        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markDeadLettered(String orderNumber, NotificationKind kind, String reason) {
        logs.findByOrderNumberAndKind(orderNumber, kind).ifPresent(entry -> {
            entry.setStatus(NotificationStatus.DLQ);
            entry.setRetryCount(entry.getRetryCount() + 1);
            entry.setFailureReason(truncate(reason));
        });
    }

    private String truncate(String reason) {
        if (reason == null) {
            return null;
        }
        return reason.length() <= 500 ? reason : reason.substring(0, 500);
    }
}
