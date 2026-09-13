package com.flashseats.bot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

/** An operator's decision about one source address. */
@Entity
@Table(name = "ip_rules")
@Getter
@Setter
@NoArgsConstructor
public class IpRule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "ip_address", nullable = false, unique = true, length = 45)
    private String ipAddress;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private IpRuleAction action;

    @Column(length = 255)
    private String reason;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** {@code null} means permanent — which is a decision someone has to remember to undo. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    public IpRule(String ipAddress, IpRuleAction action, String reason, Instant expiresAt) {
        this.ipAddress = ipAddress;
        this.action = action;
        this.reason = reason;
        this.expiresAt = expiresAt;
    }
}
