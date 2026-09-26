package com.flashseats.catalog.repository;

import com.flashseats.catalog.model.Event;
import com.flashseats.catalog.model.EventStatus;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, Long> {

    /**
     * Every event an operator is still answerable for ({@code PUBLISHED} or {@code PAUSED}, any
     * window). It is the one query behind the cached list reads, so the promotion tick needs no pooled
     * connection (ADR-051). It is not clock-dependent, which is what makes it cacheable.
     */
    List<Event> findByStatusInOrderBySaleStartTimeAsc(Collection<EventStatus> statuses);

    /**
     * Ids of events inside their sale window that an operator is still <strong>responsible for</strong>:
     * open <em>or paused</em>. "Should we admit buyers?" and "should we keep watching it?" are different
     * questions. The drift gauge and {@code StockEpoch} must keep watching a paused sale, which is exactly
     * when an operator is investigating it (ADR-048).
     *
     * <p>This is the SQL definition of the window (start inclusive, end exclusive), and
     * {@code CatalogService}'s in-memory filter must match it. {@code StockEpoch} reads this, not the
     * cache, because a fault detector should not read a cache.
     */
    @Query("""
            SELECT e.id FROM Event e
             WHERE e.status IN (com.flashseats.catalog.model.EventStatus.PUBLISHED,
                                com.flashseats.catalog.model.EventStatus.PAUSED)
               AND e.saleStartTime <= :now
               AND e.saleEndTime   >  :now
            """)
    List<Long> findManagedEventIds(@Param("now") Instant now);
}
