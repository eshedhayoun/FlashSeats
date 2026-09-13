package com.flashseats.bot.repository;

import com.flashseats.bot.model.BotAuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BotAuditLogRepository extends JpaRepository<BotAuditLog, Long> {

    /** Newest first: the question is always "what is happening right now". */
    Page<BotAuditLog> findAllByOrderByCreatedAtDesc(Pageable page);
}
