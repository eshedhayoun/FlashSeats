package com.flashseats.saleflow.controller;

import com.flashseats.saleflow.dto.SaleStateResponse;
import com.flashseats.saleflow.service.SaleStateAssembler;
import com.flashseats.shared.identity.SessionId;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The rehydration endpoint. Safe, idempotent, and cheap — four in-process facade reads.
 *
 * <p>Clients call it on mount, on {@code visibilitychange}, on {@code online}, after an SSE
 * reconnect, and after any {@code 409} or {@code 410}. It is the difference between an SPA that
 * survives a reload and one that does not.
 */
@RestController
@RequestMapping("/api/v1/sale")
public class SaleStateController {

    private final SaleStateAssembler assembler;

    public SaleStateController(SaleStateAssembler assembler) {
        this.assembler = assembler;
    }

    @GetMapping("/{eventId}/state")
    public SaleStateResponse state(@PathVariable long eventId, SessionId session) {
        return assembler.assemble(session.value(), eventId);
    }
}
