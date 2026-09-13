package com.flashseats.bot.repository;

import com.flashseats.bot.model.IpRule;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IpRuleRepository extends JpaRepository<IpRule, Long> {

    Optional<IpRule> findByIpAddress(String ipAddress);

    void deleteByIpAddress(String ipAddress);
}
