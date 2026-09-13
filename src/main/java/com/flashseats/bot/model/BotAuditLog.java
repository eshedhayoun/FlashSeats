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

/** One refused — or degraded — request. Never a successful one. */
@Entity
@Table(name = "bot_audit_logs")
@Getter
@Setter
@NoArgsConstructor
public class BotAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "session_id")
    private String sessionId;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(length = 255)
    private String path;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BotOutcome outcome;

    @Column(length = 255)
    private String detail;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public BotAuditLog(String sessionId, String ipAddress, String path, BotOutcome outcome, String detail) {
        this.sessionId = sessionId;
        this.ipAddress = ipAddress;
        this.path = path;
        this.outcome = outcome;
        this.detail = detail;
    }
}
