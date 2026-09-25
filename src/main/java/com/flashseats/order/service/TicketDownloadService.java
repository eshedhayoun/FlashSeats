package com.flashseats.order.service;

import com.flashseats.shared.ticket.TicketDocument;
import com.flashseats.shared.ticket.TicketPdfRenderer;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.stereotype.Service;

/**
 * Hands a buyer the ticket they paid for, without going through their inbox (ADR-050), so a
 * mistyped email address is recoverable.
 *
 * <p>Two beans on purpose: the authorised read is transactional in {@link OrderQueryService}, and
 * rendering is CPU work that must not hold a pooled connection (ADR-023). One class would not work,
 * because Spring's proxy does not intercept self-invocation and the read would run with no
 * transaction at all.
 */
@Service
public class TicketDownloadService {

    private final OrderQueryService orders;
    private final TicketPdfRenderer renderer;

    public TicketDownloadService(OrderQueryService orders, TicketPdfRenderer renderer) {
        this.orders = orders;
        this.renderer = renderer;
    }

    public byte[] render(String orderNumber, String sessionId, String receiptToken) {
        TicketDocument ticket = orders.ticketDocumentFor(orderNumber, sessionId, receiptToken);
        try {
            return renderer.render(ticket);
        } catch (IOException failed) {
            // Deterministic, like every other render failure here (ADR-029): the same order renders
            // the same way every time, so a retry is three identical stack traces. Let it surface.
            throw new UncheckedIOException("Could not render the ticket for " + orderNumber, failed);
        }
    }
}
