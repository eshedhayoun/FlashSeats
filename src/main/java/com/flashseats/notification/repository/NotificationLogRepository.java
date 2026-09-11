package com.flashseats.notification.repository;

import com.flashseats.notification.model.NotificationKind;
import com.flashseats.notification.model.NotificationLog;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    Optional<NotificationLog> findByOrderNumberAndKind(String orderNumber, NotificationKind kind);

    /**
     * <strong>The delivery claim.</strong> Inserts the row that makes a second send impossible, and
     * reports whether this caller is the one that inserted it.
     *
     * <p>{@code ON CONFLICT DO NOTHING} rather than an insert whose exception is caught. The
     * constraint is still what guarantees exclusivity — a preceding {@code SELECT} would be a race
     * two workers both pass — but the outcome arrives as a rowcount instead of a thrown exception,
     * which matters for more than style: a flush that violates a constraint marks the transaction
     * rollback-only, so the {@code catch} block's {@code return false} could not actually return.
     * It threw {@code UnexpectedRollbackException} at commit, and the consumer read that as a
     * delivery failure — meaning every redelivered message went to the dead-letter queue instead of
     * being quietly acknowledged as already handled.
     *
     * <p>It also puts this claim in the same shape as every other one in the system: one conditional
     * statement, and the rowcount is the whole answer (global standards §3).
     *
     * @return 1 if this caller may send, 0 if a row already exists
     */
    @Modifying(flushAutomatically = true)
    @Query(
            value =
                    """
                    INSERT INTO notification_logs (order_number, kind, recipient_email, status,
                                                   retry_count, created_at, updated_at)
                    VALUES (:orderNumber, :kind, :recipientEmail, 'PENDING', 0, now(), now())
                    ON CONFLICT (order_number, kind) DO NOTHING
                    """,
            nativeQuery = true)
    int claimIfAbsent(
            @Param("orderNumber") String orderNumber,
            @Param("kind") String kind,
            @Param("recipientEmail") String recipientEmail);

    /**
     * <strong>Re-claims a dead-lettered message.</strong> The same conditional-{@code UPDATE} shape
     * as every other claim in this system: the {@code WHERE} carries the precondition, and only
     * {@code rowcount = 1} means you may proceed.
     *
     * <p>It exists because a claim must be released when the work did not happen (global standards
     * §3). The insert-then-send guard is right, but it was also permanent: a transient SMTP outage
     * dead-lettered the message <em>and</em> kept the claim, so replaying it from the DLQ found the
     * row already there and acknowledged without ever sending. The ticket was unrecoverable without
     * someone deleting a row by hand.
     *
     * <p>{@code AND status = 'DLQ'} is what keeps this safe. A row that is {@code PENDING} — a send
     * genuinely in progress — or {@code SENT} is untouched, so this can never authorise a second
     * delivery of a message that succeeded.
     *
     * @return 1 if this caller may now send, 0 if the row is not dead-lettered
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE NotificationLog n
               SET n.status = com.flashseats.notification.model.NotificationStatus.PENDING,
                   n.retryCount = n.retryCount + 1,
                   n.failureReason = null
             WHERE n.orderNumber = :orderNumber
               AND n.kind = :kind
               AND n.status = com.flashseats.notification.model.NotificationStatus.DLQ
            """)
    int reclaimDeadLettered(
            @Param("orderNumber") String orderNumber, @Param("kind") NotificationKind kind);
}
