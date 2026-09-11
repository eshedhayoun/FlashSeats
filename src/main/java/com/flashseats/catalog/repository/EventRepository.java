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
     * this set, so a closed sale costs nothing.
     */
    @Query("""
            SELECT e.id FROM Event e
             WHERE e.status = com.flashseats.catalog.model.EventStatus.PUBLISHED
               AND e.saleStartTime <= :now
               AND e.saleEndTime   >  :now
            """)
    List<Long> findOpenEventIds(@Param("now") Instant now);
}
