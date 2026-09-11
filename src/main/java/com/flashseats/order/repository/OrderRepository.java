package com.flashseats.order.repository;

import com.flashseats.order.model.Order;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    Optional<Order> findByHoldToken(String holdToken);

    Optional<Order> findByOrderNumber(String orderNumber);

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
}
