package com.flashseats.notification.repository;

import com.flashseats.notification.model.NotificationKind;
import com.flashseats.notification.model.NotificationLog;
import com.flashseats.notification.model.NotificationStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    Optional<NotificationLog> findByOrderNumberAndKind(String orderNumber, NotificationKind kind);

    /**
     * The dead letters, newest first — what an operator reads to answer "whose ticket did not
     * arrive?".
     *
     * <p>Backed by the partial index in {@code V8__notification_dlq_index.sql}. Paged rather than
     * returning a list, because this is the one query here whose result set is unbounded in
     * principle: a broker or mail outage dead-letters everything it touches, and that is exactly the
     * moment someone opens this.
     */
    Page<NotificationLog> findByStatusOrderByUpdatedAtDesc(NotificationStatus status, Pageable page);

    /** How many dead letters there are in total, so a paged listing can say what it is a page of. */
    long countByStatus(NotificationStatus status);

    /**
     * <strong>The delivery claim</strong>, as a rowcount from {@code ON CONFLICT DO NOTHING}. A caught
     * constraint violation would mark the transaction rollback-only and turn "already handled" into a
     * failure at commit (ADR-038).
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
     * <strong>Re-claims a dead-lettered message</strong> so a replay actually sends (ADR-038).
     * {@code AND status = 'DLQ'} keeps it safe: a {@code PENDING} or {@code SENT} row is never touched,
     * so this cannot authorise a second delivery.
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
