package com.flashseats.saleflow.dto;

import com.flashseats.catalog.facade.EventWindowStatus;
import com.flashseats.queue.facade.QueuePhase;
import java.time.Instant;
import java.util.List;

/**
 * Everything a client needs to render the right screen after a reload.
 *
 * <p>{@code serverTime} is on every response so every countdown in the UI — queue estimate,
 * admission session, hold timer — runs on the server's clock rather than the device's (ADR-016).
 *
 * <p>{@code partial} names any section that could not be read. A rehydration endpoint that returns
 * {@code 500} because one sub-read failed is worse than one that returns most of the picture: the
 * client renders what it has and retries the rest.
 */
public record SaleStateResponse(
        long eventId,
        EventWindowStatus windowStatus,
        Instant serverTime,
        QueueSection queue,
        HoldSection hold,
        OrderSection order,
        List<String> partial) {

    public record QueueSection(
            QueuePhase state,
            Integer position,
            Integer estWaitSeconds,
            Instant admissionExpiresAt,
            String passToken) {}

    public record HoldSection(
            String holdToken, long tierId, int quantity, Instant expiresAt, long ttlRemainingSeconds) {}

    public record OrderSection(String orderNumber, String status) {}
}
