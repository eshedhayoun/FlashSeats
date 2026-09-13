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
     * Every event an operator is still answerable for — {@code PUBLISHED} or {@code PAUSED}, any
     * window.
     *
     * <p>One query behind all three list reads below, so that <strong>the promotion tick needs no
     * database connection at all</strong> (ADR-051). The tick existed to protect the connection pool
     * and could not run when the pool was under pressure: {@code findOpenEventIds} waited on the same
     * queue as the buyers it was meant to admit, and a 16-second wait inside a one-second tick means
     * nobody is promoted, the waiting room does not drain, and the buyers keep polling. That is a
     * feedback loop, not a slow query.
     *
     * <p>Unlike the three derived reads this is <em>not</em> parameterised by the clock, which is what
     * makes it cacheable: the window comparison happens in memory against a snapshot.
     */
    List<Event> findByStatusInOrderBySaleStartTimeAsc(Collection<EventStatus> statuses);

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
     * <p>Reusing "is it open?" for those two is the easy version of this change and the wrong one.
     *
     * <p><strong>This is the surviving SQL definition of the window</strong>, and the one
     * {@code CatalogService}'s in-memory filter is written against: sale start inclusive, sale end
     * exclusive. The open-events query that used to sit above it is gone — it ran once per promotion
     * tick, which made the promoter wait on the pool it was protecting (ADR-051) — so if this
     * predicate ever changes, the in-memory one changes with it or the drift gauge and the promoter
     * disagree about which sales exist.
     *
     * <p>{@code StockEpoch} keeps calling this rather than the cached path deliberately: it is the
     * Redis-restart guard, and a fault detector should not read a cache.
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
