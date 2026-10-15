package com.flashseats.order.repository;

import com.flashseats.order.model.Order;
import com.flashseats.order.model.OrderStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByHoldToken(String holdToken);

    Optional<Order> findByOrderNumber(String orderNumber);

    /**
     * The order, row-locked until the caller's transaction ends (ADR-075). Confirming reads the status
     * it is about to overwrite, so the read has to hold the row: otherwise a concurrent write between
     * the read and the flush either goes unseen or, through the version check, fails a confirmation
     * that was entitled to succeed.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT o FROM Order o WHERE o.orderNumber = :orderNumber")
    Optional<Order> lockByOrderNumber(@Param("orderNumber") String orderNumber);

    /**
     * Moves an order to {@code to} only if it is still in one of {@code from} (ADR-064). This is how
     * a settled charge's ending is decided: confirming requires the same row, so a refund claimed
     * here can no longer be confirmed, and a confirmed order can no longer be claimed.
     *
     * <p>It bumps {@code version} itself, because a bulk update bypasses the entity: an {@code Order}
     * loaded before this ran must fail its own flush rather than write over it. No
     * {@code clearAutomatically}, for the reason {@code TicketHoldRepository.settle} gives.
     *
     * @return 1 if this caller made the transition, 0 if the order had already moved on
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Order o
               SET o.status = :to,
                   o.failureReason = :reason,
                   o.version = o.version + 1,
                   o.updatedAt = :now
             WHERE o.orderNumber = :orderNumber
               AND o.status IN :from
            """)
    int transition(
            @Param("orderNumber") String orderNumber,
            @Param("from") Collection<OrderStatus> from,
            @Param("to") OrderStatus to,
            @Param("reason") String reason,
            @Param("now") Instant now);

    /**
     * {@link #transition} to {@code REFUNDED} that also records <strong>which charge</strong> is going
     * back (ADR-075). An order then names the one charge it ended with, confirmed or refunded, and any
     * other charge for the same hold is recognisably a second one.
     *
     * @return 1 if this caller claimed the refund, 0 if the order had already moved on
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Order o
               SET o.status = com.flashseats.order.model.OrderStatus.REFUNDED,
                   o.failureReason = :reason,
                   o.paymentTransactionRef = :transactionReference,
                   o.gatewayReference = :gatewayReference,
                   o.version = o.version + 1,
                   o.updatedAt = :now
             WHERE o.orderNumber = :orderNumber
               AND o.status IN :from
            """)
    int claimRefund(
            @Param("orderNumber") String orderNumber,
            @Param("from") Collection<OrderStatus> from,
            @Param("reason") String reason,
            @Param("transactionReference") String transactionReference,
            @Param("gatewayReference") String gatewayReference,
            @Param("now") Instant now);

    /**
     * Puts a {@code FAILED} or stranded order back in flight, if nobody has touched it since it was
     * read. Two retries racing to resume the same order both read the same version; one wins.
     *
     * @return 1 if this caller resumed it, 0 if another request got there first
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE Order o
               SET o.status = com.flashseats.order.model.OrderStatus.PENDING,
                   o.version = o.version + 1,
                   o.updatedAt = :now
             WHERE o.orderNumber = :orderNumber
               AND o.version = :version
            """)
    int resume(
            @Param("orderNumber") String orderNumber,
            @Param("version") long version,
            @Param("now") Instant now);

    /**
     * This session's most recent order for an event, <strong>whatever its status</strong>.
     *
     * <p>Deliberately unfiltered (ADR-037). Filtering on {@code PENDING} meant rehydration could
     * never surface a completed purchase, so a buyer who reloaded their receipt page was shown the
     * landing page and invited to join the queue for seats they already owned.
     */
    Optional<Order> findFirstByUserSessionIdAndEventIdOrderByCreatedAtDesc(
            String userSessionId, long eventId);

    /**
     * Human-facing order numbers come from a database sequence rather than a counter in application
     * memory, so three replicas cannot mint the same one.
     */
    @Query(value = "SELECT nextval('order_number_seq')", nativeQuery = true)
    long nextOrderNumberValue();

    /** Confirmed seats per tier, for the stock invariant check. */
    @Query("""
            SELECT COALESCE(SUM(i.quantity), 0)
              FROM OrderItem i, Order o
             WHERE i.orderId = o.id
               AND i.tierId = :tierId
               AND o.status = com.flashseats.order.model.OrderStatus.CONFIRMED
            """)
    int sumConfirmedQuantityForTier(@Param("tierId") long tierId);

    /**
     * Takes the per-event stock-rebuild lock, {@code pg_try_advisory_xact_lock}: transaction-scoped,
     * so a crashed replica cannot leak it (ADR-022). It is here because {@code order} runs the rebuild.
     *
     * @return false when another rebuild already holds it
     */
    @Query(
            value = "SELECT pg_try_advisory_xact_lock(hashtext('stock-rebuild:' || :eventId))",
            nativeQuery = true)
    boolean tryStockRebuildLock(@Param("eventId") long eventId);
}
