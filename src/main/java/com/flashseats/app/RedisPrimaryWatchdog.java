package com.flashseats.app;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.Properties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Notices when this replica's Redis connection has ended up on a node that is not the primary, and
 * reconnects through Sentinel (ADR-073).
 *
 * <p>A live failover needs no help: the old primary goes away, its connections drop, and Lettuce asks
 * the Sentinels again on reconnect. The failure this exists for is the one where nothing drops — a
 * connection that is healthy at the TCP level to a node that has since been made a replica. Every write
 * then answers {@code READONLY}, every request a bare {@code 500}, and before this the only cure was
 * restarting the replicas by hand. The pub/sub listeners hold their own connections, so they are
 * restarted too; the hold-expiry listener on a replica would otherwise hear no expiries at all.
 *
 * <p>Sentinel deployments only: standalone Redis has no other node to move to.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "spring.data.redis.sentinel.master")
public class RedisPrimaryWatchdog {

    private final LettuceConnectionFactory connections;
    private final List<RedisMessageListenerContainer> listeners;
    private final Counter reconnects;

    public RedisPrimaryWatchdog(
            LettuceConnectionFactory connections,
            List<RedisMessageListenerContainer> listeners,
            MeterRegistry meters) {
        this.connections = connections;
        this.listeners = listeners;
        this.reconnects = Counter.builder("flashseats.redis.primary.reconnects")
                .description("Times this replica found itself on a non-primary Redis node and reconnected")
                .register(meters);
    }

    @Scheduled(fixedDelay = 5_000, initialDelay = 5_000)
    public void checkPrimary() {
        String role;
        try {
            role = roleOfConnectedNode();
        } catch (RuntimeException unreachable) {
            // Redis down or mid-failover: the connection reconnects through Sentinel on its own.
            log.debug("Could not ask Redis for its role", unreachable);
            return;
        }
        if (role == null || "master".equals(role)) {
            return;
        }
        log.warn("Connected Redis node reports role '{}', not master; reconnecting through Sentinel", role);
        reconnects.increment();
        connections.resetConnection();
        for (RedisMessageListenerContainer listener : listeners) {
            listener.stop();
            listener.start();
        }
    }

    private String roleOfConnectedNode() {
        try (RedisConnection connection = connections.getConnection()) {
            Properties replication = connection.serverCommands().info("replication");
            return replication == null ? null : replication.getProperty("role");
        }
    }
}
