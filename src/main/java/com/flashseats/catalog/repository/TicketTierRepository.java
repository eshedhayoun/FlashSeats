package com.flashseats.catalog.repository;

import com.flashseats.catalog.model.TicketTier;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TicketTierRepository extends JpaRepository<TicketTier, Long> {

    List<TicketTier> findByEventIdOrderByPriceCentsDesc(long eventId);
}
