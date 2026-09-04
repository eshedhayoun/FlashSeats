package com.flashseats.queue.controller;

import com.flashseats.queue.dto.AdmitResponse;
import com.flashseats.queue.dto.JoinQueueRequest;
import com.flashseats.queue.dto.QueueStatusResponse;
import com.flashseats.queue.service.QueueService;
import com.flashseats.queue.service.SseEmitterRegistry;
import com.flashseats.shared.identity.SessionId;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** The waiting room. */
@RestController
@RequestMapping("/api/v1/queue")
public class QueueController {

    /**
     * A wait can legitimately last an hour, so the stream must outlive any default. Nginx is
     * configured with a matching {@code proxy_read_timeout}; a shorter value at either end severs
     * every stream on a timer and looks to the buyer like a broken connection.
     */
    private static final long STREAM_TIMEOUT_MS = Duration.ofHours(1).toMillis();

    private final QueueService queue;
    private final SseEmitterRegistry emitters;

    public QueueController(QueueService queue, SseEmitterRegistry emitters) {
        this.queue = queue;
        this.emitters = emitters;
    }

    /** Joins the line. Idempotent — rejoining preserves the original position. */
    @PostMapping("/join")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public QueueStatusResponse join(@Valid @RequestBody JoinQueueRequest request, SessionId session) {
        return queue.join(session.value(), request.eventId());
    }

    /**
     * The polling fallback, and the reconnect path.
     *
     * <p>Returns the pass if one was minted while the client was away, which is what stops a
     * promotion from being lost to a dead socket (ADR-007).
     */
    @GetMapping("/status")
    public QueueStatusResponse status(@RequestParam long eventId, SessionId session) {
        return queue.status(session.value(), eventId);
    }

    /** Exchanges the single-use pass for a browse session. */
    @PostMapping("/admit")
    public AdmitResponse admit(
            @RequestParam long eventId,
            @RequestHeader("X-Queue-Pass-Token") String passToken,
            SessionId session) {
        return queue.admit(session.value(), eventId, passToken);
    }

    /**
     * Live position updates.
     *
     * <p>Frames: {@code position-update} (clamped monotonic), {@code queue-promoted},
     * {@code sale-exhausted}, {@code sale-closed}, plus comment heartbeats. Every frame carries an
     * id so a reconnect can send {@code Last-Event-ID}.
     *
     * <p>If the stream cannot be established at all the client polls {@code /queue/status}, which
     * returns the same information — the buyer should never have to care which transport is live.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam long eventId, SessionId session) {
        SseEmitter emitter = emitters.register(session.value(), eventId, STREAM_TIMEOUT_MS);

        // Send the current position immediately: an empty stream for the first two seconds looks
        // like a failure to connect.
        var state = queue.getQueueState(session.value(), eventId);
        if (state.position() != null) {
            emitters.sendPosition(session.value(), state.position(), state.estWaitSeconds());
        }
        return emitter;
    }
}
