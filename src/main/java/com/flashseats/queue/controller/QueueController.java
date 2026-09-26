package com.flashseats.queue.controller;

import com.flashseats.queue.dto.AdmitRequest;
import com.flashseats.queue.dto.AdmitResponse;
import com.flashseats.queue.dto.JoinQueueRequest;
import com.flashseats.queue.dto.QueueStatusResponse;
import com.flashseats.queue.service.QueueBroadcaster;
import com.flashseats.queue.service.QueueService;
import com.flashseats.shared.identity.SessionId;
import com.flashseats.shared.web.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
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

    private final QueueService queue;
    private final QueueBroadcaster broadcaster;

    public QueueController(QueueService queue, QueueBroadcaster broadcaster) {
        this.queue = queue;
        this.broadcaster = broadcaster;
    }

    /** Joins the line. Idempotent: rejoining keeps the original position (ADR-008). */
    @PostMapping("/join")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public QueueStatusResponse join(
            @Valid @RequestBody JoinQueueRequest request, SessionId session, HttpServletRequest http) {
        return queue.join(
                session.value(), request.eventId(), request.recaptchaToken(), ClientAddress.of(http));
    }

    @GetMapping("/status")
    public QueueStatusResponse status(@RequestParam long eventId, SessionId session) {
        return queue.status(session.value(), eventId);
    }

    /** Exchanges the single-use pass for an admission. {@code eventId} is in the body (FE_SPEC §2). */
    @PostMapping("/admit")
    public AdmitResponse admit(
            @Valid @RequestBody AdmitRequest request,
            @RequestHeader("X-Queue-Pass-Token") String passToken,
            SessionId session) {
        return queue.admit(session.value(), request.eventId(), passToken);
    }

    /**
     * Live position updates: {@code position-update}, {@code queue-promoted},
     * {@code sale-exhausted}, {@code sale-closed}, plus comment heartbeats. A client that cannot hold
     * a stream polls {@code /queue/status}, which returns the same information.
     *
     * <p>{@code Last-Event-ID} is what a browser's EventSource sends by itself; the query parameter
     * is for clients that cannot set a header.
     */
    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam long eventId,
            @RequestParam(value = "lastEventId", required = false) String lastEventId,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader,
            SessionId session) {
        return broadcaster.connect(
                session.value(), eventId, lastEventIdHeader != null ? lastEventIdHeader : lastEventId);
    }
}
