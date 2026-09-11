package com.flashseats.hold.controller;

import com.flashseats.hold.dto.CreateHoldRequest;
import com.flashseats.hold.dto.HoldResponse;
import com.flashseats.hold.service.HoldService;
import com.flashseats.shared.identity.SessionId;
import jakarta.validation.Valid;
import java.time.Clock;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seat reservations.
 *
 * <p>Every endpoint is ownership-checked against the {@link SessionId} resolved from the signed
 * cookie. The read is checked too — leaving it open would allow hold-token enumeration.
 */
@RestController
@RequestMapping("/api/v1/holds")
public class HoldController {

    private final HoldService holds;
    private final Clock clock;

    public HoldController(HoldService holds, Clock clock) {
        this.holds = holds;
        this.clock = clock;
    }

    /**
     * Reserves seats.
     *
     * <p>Requires a live admission session, <strong>not</strong> a queue pass — that was spent at
     * {@code POST /queue/admit}. The session survives this call, so releasing these seats and picking
     * a different tier costs the buyer nothing (ADR-020).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HoldResponse create(
            @Valid @RequestBody CreateHoldRequest request,
            @RequestHeader(value = "X-Admission-Token", required = false) String admissionToken,
            SessionId session) {

        return HoldResponse.of(
                holds.createHold(
                        session.value(),
                        request.eventId(),
                        request.tierId(),
                        request.quantity(),
                        admissionToken),
                clock.instant());
    }

    /** Re-syncs the countdown. The client polls this rather than trusting its own clock. */
    @GetMapping("/{holdToken}")
    public HoldResponse get(@PathVariable String holdToken, SessionId session) {
        return HoldResponse.of(holds.requireActiveHold(holdToken, session.value()), clock.instant());
    }

    /**
     * Cancels a reservation and returns the seats immediately.
     *
     * <p>The buyer's place in the sale survives this, so they can pick a different tier without
     * re-queueing (ADR-020).
     */
    @DeleteMapping("/{holdToken}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable String holdToken, SessionId session) {
        holds.release(holdToken, session.value());
    }
}
