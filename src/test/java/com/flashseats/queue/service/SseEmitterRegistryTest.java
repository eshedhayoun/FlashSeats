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
    @DisplayName("Each event knows only its own watchers")
    void indexesSessionsByEvent() {
        registry.open("a", 1L, 30_000);
        registry.open("b", 1L, 30_000);
        registry.open("c", 2L, 30_000);

        assertThat(registry.sessionsWatching(1L)).containsExactlyInAnyOrder("a", "b");
        assertThat(registry.sessionsWatching(2L)).containsExactly("c");
        assertThat(registry.watchedEventIds()).containsExactlyInAnyOrder(1L, 2L);
    }

    /**
     * Queued in two sales at once is a supported state (FE_SPEC rule 5). Keyed by session alone, the
     * second stream completed the first, and the two tabs reconnected over each other for ever (ADR-070).
     */
    @Test
    @DisplayName("One session can watch two sales at once, and each stream stays open")
    void oneSessionWatchesTwoSales() {
        var first = registry.open("a", 1L, 30_000);
        var second = registry.open("a", 2L, 30_000);

        assertThat(registry.sessionsWatching(1L)).containsExactly("a");
        assertThat(registry.sessionsWatching(2L)).containsExactly("a");
        assertThat(registry.send(first, "position-update", Map.of("position", 3), null)).isTrue();
        assertThat(registry.send(second, "position-update", Map.of("position", 9), null)).isTrue();
    }

    @Test
    @DisplayName("Two tabs on one sale are two streams, and closing one leaves the other")
    void twoTabsAreTwoStreams() {
        var firstTab = registry.open("a", 1L, 30_000);
        var secondTab = registry.open("a", 1L, 30_000);

        firstTab.emitter().complete();
        // Completion callbacks need a servlet container; a dead socket is found the same way a sweep
        // finds one, on the next write.
        registry.send(firstTab, "position-update", Map.of("position", 1), null);

        assertThat(registry.send(secondTab, "position-update", Map.of("position", 1), null)).isTrue();
        assertThat(registry.sessionsWatching(1L)).containsExactly("a");
    }

    @Test
    @DisplayName("A frame for one sale never reaches the session's stream for another")
    void framesAreScopedToTheirEvent() {
        registry.open("a", 2L, 30_000);

        assertThat(registry.send("a", 1L, "queue-promoted", Map.of("passToken", "for-sale-1"))).isFalse();
        assertThat(registry.send("a", 2L, "position-update", Map.of("position", 1))).isTrue();
    }

    @Test
    @DisplayName("A session's streams for one sale are capped, and the oldest goes first")
    void streamsPerSessionAreCapped() {
        var oldest = registry.open("a", 1L, 30_000);
        for (int tab = 1; tab < SseEmitterRegistry.MAX_STREAMS_PER_SESSION; tab++) {
            registry.open("a", 1L, 30_000);
        }
        var newest = registry.open("a", 1L, 30_000);

        assertThat(registry.send(oldest, "position-update", Map.of("position", 1), null)).isFalse();
        assertThat(registry.send(newest, "position-update", Map.of("position", 1), null)).isTrue();
    }

    @Test
    @DisplayName("Closing one event removes only that event's sessions")
    void closeAllRemovesOnlyThatEventsSessions() {
        registry.open("a", 1L, 30_000);
        registry.open("b", 1L, 30_000);
        registry.open("c", 2L, 30_000);

        registry.closeAll(1L, "sale-closed", Map.of("closedAt", "now"));

        assertThat(registry.sessionsWatching(1L)).isEmpty();
        assertThat(registry.sessionsWatching(2L)).containsExactly("c");
        assertThat(registry.watchedEventIds()).containsExactly(2L);
    }
}
