package com.flashseats.catalog.repository;

import com.flashseats.catalog.model.Event;
import com.flashseats.catalog.model.EventStatus;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface EventRepository extends JpaRepository<Event, Long> {

    List<Event> findByStatusOrderBySaleStartTimeAsc(EventStatus status);

    /**
     * Ids of events whose sale window is open right now. The promotion worker ticks over exactly
     * this set, so a closed sale costs nothing — and a <strong>paused</strong> one drops out of it,
     * which is what pausing means.
     */
    @Query("""
            SELECT e.id FROM Event e
             WHERE e.status = com.flashseats.catalog.model.EventStatus.PUBLISHED
               AND e.saleStartTime <= :now
               AND e.saleEndTime   >  :now
            """)
    List<Long> findOpenEventIds(@Param("now") Instant now);

    /**
     * Ids of events inside their sale window that an operator is still <strong>responsible
     * for</strong> — open or paused.
     *
     * <p>This exists because "should we admit buyers to it?" and "should we keep watching it?" are
     * different questions, and pausing separates them for the first time. Two callers need the
     * second one:
     *
     * <ul>
     *   <li>the {@code stock.drift} gauge — pausing a sale is precisely what an operator does while
     *       investigating a counter, so going blind on drift at that moment is backwards;
     *   <li>{@code StockEpoch}, the Redis-restart guard — a paused event whose counters were rolled
     *       back by a restart must still be flagged. Dropping it would mean the flag is only raised
     *       once someone resumes the sale, which is to say once it has started selling from them.
     * </ul>
     *
     * <p>Reusing {@code findOpenEventIds} for those two is the easy version of this change and the
     * wrong one.
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
