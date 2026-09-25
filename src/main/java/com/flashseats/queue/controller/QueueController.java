package com.flashseats.queue.controller;

import com.flashseats.bot.facade.BotFacade;
import com.flashseats.queue.dto.AdmitRequest;
import com.flashseats.queue.dto.AdmitResponse;
import com.flashseats.queue.dto.JoinQueueRequest;
import com.flashseats.queue.dto.QueueStatusResponse;
import com.flashseats.queue.service.QueueService;
import com.flashseats.queue.service.QueueReplayService;
import com.flashseats.queue.service.SseEmitterRegistry;
import com.flashseats.shared.identity.SessionId;
import com.flashseats.shared.web.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
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
    private final QueueReplayService replay;
    private final BotFacade bots;

    public QueueController(
            QueueService queue, SseEmitterRegistry emitters, QueueReplayService replay, BotFacade bots) {
        this.queue = queue;
        this.emitters = emitters;
        this.replay = replay;
        this.bots = bots;
    }

    /**
     * Joins the line. Idempotent — rejoining preserves the original position.
     *
     * <p><strong>The one place a challenge is worth its cost.</strong> Join is where an automated
     * buyer gains its advantage: it is the front of the line, it is cheap to repeat, and a session
     * id costs nothing to mint — so ADR-011's per-session bucket does not constrain a determined
     * attacker at all. Everything after this point is already gated by a queue pass and an admission
     * the server issued.
     *
     * <p>Verification <strong>fails open</strong>: a provider that is unconfigured, slow or broken
     * lets the visitor through and is audited as degraded (ADR-011, ADR-055).
     */
    @PostMapping("/join")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public QueueStatusResponse join(
            @Valid @RequestBody JoinQueueRequest request,
            SessionId session,
            HttpServletRequest http) {

        // The address the rate limiter already resolved, not getRemoteAddr(): behind nginx the
        // latter is always the proxy, so every audit row in the deployment that matters would
        // record the same meaningless value (ADR-039 resolves X-Forwarded-For in one place).
        bots.verifyHuman(
                session.value(),
                request.recaptchaToken(),
                ClientAddress.of(http));
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

    /**
     * Exchanges the single-use pass for a browse session.
     *
     * <p>{@code eventId} arrives in the body, like every other {@code POST} here and as
     * {@code FE_SPEC.md} §2 specifies. It was a query parameter, which worked only because the demo
     * client was written against the code rather than against the contract.
     */
    @PostMapping("/admit")
    public AdmitResponse admit(
            @Valid @RequestBody AdmitRequest request,
            @RequestHeader("X-Queue-Pass-Token") String passToken,
            SessionId session) {
        return queue.admit(session.value(), request.eventId(), passToken);
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
    public SseEmitter stream(
            @RequestParam long eventId,
            @RequestParam(value = "lastEventId", required = false) String lastEventId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader,
            SessionId session) {
        SseEmitter emitter = emitters.register(session.value(), eventId, STREAM_TIMEOUT_MS);

        String replayFrom = lastEventIdHeader != null ? lastEventIdHeader : lastEventId;
        if (replayFrom != null) {
            for (var frame : replay.after(eventId, replayFrom)) {
                if (frame.isBroadcast() || session.value().equals(frame.sessionId())) {
                    emitters.send(session.value(), frame.type(), frame.data(), frame.id());
                }
            }
        }

        // Flush the stream immediately even if this buyer is already promoted, admitted or
        // exhausted and therefore has no position frame to send.
        emitters.comment(session.value(), "connected");

        // Send the current position immediately when one exists: an empty stream for the first two
        // seconds looks like a failure to connect.
        var state = queue.getQueueState(session.value(), eventId);
        if (state.position() != null) {
            emitters.sendPosition(session.value(), state.position(), state.estWaitSeconds());
        }
        return emitter;
    }
}
