package com.flashseats.catalog.repository;

import com.flashseats.catalog.model.TicketTier;
import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketTierRepository extends JpaRepository<TicketTier, Long> {

    List<TicketTier> findByEventIdOrderByPriceCentsDesc(long eventId);

    List<TicketTier> findByEventIdInOrderByPriceCentsDesc(Collection<Long> eventIds);

    @Modifying
    @Query("DELETE FROM TicketTier t WHERE t.eventId = :eventId")
    int deleteByEventId(@Param("eventId") long eventId);
}
