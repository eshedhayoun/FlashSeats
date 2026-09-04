package com.flashseats.notification.repository;

import com.flashseats.notification.model.NotificationKind;
import com.flashseats.notification.model.NotificationLog;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, Long> {

    Optional<NotificationLog> findByOrderNumberAndKind(String orderNumber, NotificationKind kind);
}
