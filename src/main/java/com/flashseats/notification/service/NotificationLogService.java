package com.flashseats.notification.service;

import com.flashseats.notification.model.NotificationKind;
import com.flashseats.notification.model.NotificationStatus;
import com.flashseats.notification.repository.NotificationLogRepository;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
@Service
public class NotificationLogService {

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
     * <p><strong>Insert first, send second.</strong> The unique constraint — not a preceding
     * {@code SELECT} — is the guard, because the constraint is atomic and a read is not. Two workers
     * handling the same redelivered message would both pass a {@code SELECT} and both send, and the
     * buyer would get two tickets (ADR-015).
     *
     * <p>{@code REQUIRES_NEW} so the claim commits on its own, independently of anything the caller
     * does afterwards.
     *
     * <p><strong>A dead-lettered row is re-claimable</strong> (ADR-038). The claim guards a send in
     * progress, so it has to be released when the send did not happen — otherwise an SMTP outage
     * dead-letters the message and permanently consumes its own guard, and replaying it from the
     * DLQ silently acknowledges without sending. The re-claim is itself a conditional {@code UPDATE}
     * on {@code status = 'DLQ'}, so a {@code PENDING} or {@code SENT} row is never disturbed and
     * this can never authorise a second delivery of a message that worked.
     *
     * @return true if this caller may send; false if someone already has, or is doing so now
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(String orderNumber, NotificationKind kind, String recipientEmail) {
        // Two conditional statements, each answering with a rowcount. Neither can throw, so neither
        // can leave this transaction rollback-only — which is what the earlier insert-and-catch did,
        // making "already handled, acknowledge quietly" impossible to actually return.
        if (logs.claimIfAbsent(orderNumber, kind.name(), recipientEmail) == 1) {
            return true;
        }
        if (logs.reclaimDeadLettered(orderNumber, kind) == 1) {
            log.info("Re-claimed dead-lettered {} for {}", kind, orderNumber);
            return true;
        }
        log.debug("{} for {} was already handled", kind, orderNumber);
        return false;
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
