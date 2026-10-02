package com.flashseats.app;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisServerCommands;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * A connection healthy at the TCP level to a node that has since been demoted answers every write
 * READONLY, and nothing drops it. The watchdog notices and reconnects through Sentinel (ADR-073).
 */
@DisplayName("A replica stranded on a demoted Redis node reconnects to the primary")
class RedisPrimaryWatchdogTest {

    private final LettuceConnectionFactory connections = mock(LettuceConnectionFactory.class);
    private final RedisConnection connection = mock(RedisConnection.class);
    private final RedisServerCommands server = mock(RedisServerCommands.class);
    private final RedisMessageListenerContainer listener = mock(RedisMessageListenerContainer.class);

    private final RedisPrimaryWatchdog watchdog =
            new RedisPrimaryWatchdog(connections, List.of(listener), new SimpleMeterRegistry());

    private void connectedNodeReports(String role) {
        Properties replication = new Properties();
        replication.setProperty("role", role);
        when(connections.getConnection()).thenReturn(connection);
        when(connection.serverCommands()).thenReturn(server);
        when(server.info("replication")).thenReturn(replication);
    }

    @Test
    @DisplayName("On a replica, the connection and the listeners are rebuilt")
    void aDemotedNodeIsLeft() {
        connectedNodeReports("slave");

        watchdog.checkPrimary();

        verify(connections).resetConnection();
        verify(listener).stop();
        verify(listener).start();
    }

    @Test
    @DisplayName("On the primary, nothing is touched")
    void thePrimaryIsLeftAlone() {
        connectedNodeReports("master");

        watchdog.checkPrimary();

        verify(connections, never()).resetConnection();
        verify(listener, never()).stop();
    }

    @Test
    @DisplayName("With Redis unreachable it waits: the connection reconnects by itself")
    void anUnreachableRedisIsLeftToReconnect() {
        when(connections.getConnection()).thenThrow(new IllegalStateException("down"));

        watchdog.checkPrimary();

        verify(connections, never()).resetConnection();
    }
}
