package com.flashseats.notification.service;

import com.flashseats.notification.dto.DeadLetterResponse;
import com.flashseats.notification.model.NotificationKind;
import com.flashseats.notification.model.NotificationStatus;
import com.flashseats.notification.repository.NotificationLogRepository;
import java.time.Clock;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
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
     * Claims the right to send, by inserting the row that makes a second send impossible. The unique
     * constraint is the guard, never a preceding {@code SELECT} (ADR-015). {@code REQUIRES_NEW}, so the
     * claim commits on its own. A {@code DLQ} row is re-claimable, so a replay sends (ADR-038).
     *
     * @return true if this caller may send; false if someone already has, or is doing so now
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claim(String orderNumber, NotificationKind kind, String recipientEmail) {
        // Two conditional statements answering with rowcounts. Neither throws, so neither can leave
        // this transaction rollback-only (ADR-038).
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

    /**
     * The dead letters an operator can act on, newest first.
     *
     * <p>{@code DLQ} is the one status that means <strong>the work did not happen</strong>, which is
     * what makes every row here safely replayable — and why nothing writes {@code DLQ} once the mail
     * server has accepted a message (ADR-038).
     */
    @Transactional(readOnly = true)
    public List<DeadLetterResponse> deadLetters(int page, int size) {
        return logs
                .findByStatusOrderByUpdatedAtDesc(
                        NotificationStatus.DLQ, PageRequest.of(page, size))
                .map(entry -> new DeadLetterResponse(
                        entry.getOrderNumber(),
                        entry.getKind(),
                        entry.getRecipientEmail(),
                        entry.getRetryCount(),
                        entry.getFailureReason(),
                        entry.getUpdatedAt()))
                .getContent();
    }

    @Transactional(readOnly = true)
    public long deadLetterCount() {
        return logs.countByStatus(NotificationStatus.DLQ);
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
