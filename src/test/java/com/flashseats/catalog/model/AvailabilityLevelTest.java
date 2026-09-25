package com.flashseats.catalog.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.flashseats.catalog.facade.CatalogFacade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The public availability rule — buckets, never counts (ADR-027). */
@DisplayName("AvailabilityLevel.of")
class AvailabilityLevelTest {

    private static final int TEN_PERCENT = 10;

    @Test
    @DisplayName("zero remaining is sold out")
    void zeroIsSoldOut() {
        assertThat(AvailabilityLevel.of(0, 500, TEN_PERCENT)).isEqualTo(AvailabilityLevel.SOLD_OUT);
    }

    @Test
    @DisplayName("at or below the threshold is limited")
    void atThresholdIsLimited() {
        assertThat(AvailabilityLevel.of(50, 500, TEN_PERCENT)).isEqualTo(AvailabilityLevel.LIMITED);
        assertThat(AvailabilityLevel.of(1, 500, TEN_PERCENT)).isEqualTo(AvailabilityLevel.LIMITED);
    }

    @Test
    @DisplayName("above the threshold is plenty")
    void aboveThresholdIsPlenty() {
        assertThat(AvailabilityLevel.of(51, 500, TEN_PERCENT)).isEqualTo(AvailabilityLevel.PLENTY);
        assertThat(AvailabilityLevel.of(500, 500, TEN_PERCENT)).isEqualTo(AvailabilityLevel.PLENTY);
    }

    @Test
    @DisplayName("a tiny tier never reports limited for its whole capacity")
    void smallTiersDoNotRoundToLimited() {
        // 10% of 5 rounds to 0, so any remaining seat is PLENTY rather than a permanent warning.
        assertThat(AvailabilityLevel.of(5, 5, TEN_PERCENT)).isEqualTo(AvailabilityLevel.PLENTY);
        assertThat(AvailabilityLevel.of(1, 5, TEN_PERCENT)).isEqualTo(AvailabilityLevel.PLENTY);
    }

    @Test
    @DisplayName("a missing counter is UNKNOWN, never SOLD_OUT")
    void missingCounterIsUnknown() {
        // ADR-040. The call site used to clamp with Math.max(remaining, 0), which turned the fault
        // code into zero and published "we cannot read our inventory" as "this tier is gone" — on
        // the landing page, to every visitor, for a sale that had not started.
        assertThat(AvailabilityLevel.of(CatalogFacade.COUNTER_UNAVAILABLE, 500, TEN_PERCENT))
                .isEqualTo(AvailabilityLevel.UNKNOWN);
    }

    @Test
    @DisplayName("UNKNOWN is distinct from SOLD_OUT — the whole point of the level")
    void unknownIsNotSoldOut() {
        assertThat(AvailabilityLevel.of(CatalogFacade.COUNTER_UNAVAILABLE, 500, TEN_PERCENT))
                .isNotEqualTo(AvailabilityLevel.of(0, 500, TEN_PERCENT));
    }
}
