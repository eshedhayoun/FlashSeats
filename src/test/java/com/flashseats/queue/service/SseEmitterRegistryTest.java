package com.flashseats.queue.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("SseEmitterRegistry")
class SseEmitterRegistryTest {

    private final SseEmitterRegistry registry = new SseEmitterRegistry(new ObjectMapper());

    @Test
    @DisplayName("Sessions are indexed by event")
    void sessionsAreIndexedByEvent() {
        registry.register("a", 1L, 30_000);
        registry.register("b", 1L, 30_000);
        registry.register("c", 2L, 30_000);

        assertThat(registry.sessionsWatching(1L)).containsExactlyInAnyOrder("a", "b");
        assertThat(registry.sessionsWatching(2L)).containsExactly("c");
        assertThat(registry.watchedEventIds()).containsExactlyInAnyOrder(1L, 2L);
    }

    @Test
    @DisplayName("Reconnect moves a session to its new event")
    void reconnectMovesSessionToNewEvent() {
        registry.register("a", 1L, 30_000);
        registry.register("a", 2L, 30_000);

        assertThat(registry.sessionsWatching(1L)).isEmpty();
        assertThat(registry.sessionsWatching(2L)).containsExactly("a");
        assertThat(registry.watchedEventIds()).containsExactly(2L);
    }

    @Test
    @DisplayName("Closing one event removes only that event's sessions")
    void closeAllRemovesOnlyThatEventsSessions() {
        registry.register("a", 1L, 30_000);
        registry.register("b", 1L, 30_000);
        registry.register("c", 2L, 30_000);

        registry.closeAll(1L, "sale-closed", Map.of("closedAt", "now"));

        assertThat(registry.sessionsWatching(1L)).isEmpty();
        assertThat(registry.sessionsWatching(2L)).containsExactly("c");
        assertThat(registry.watchedEventIds()).containsExactly(2L);
    }
}
