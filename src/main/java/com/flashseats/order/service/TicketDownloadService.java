package com.flashseats.order.service;

import com.flashseats.shared.ticket.TicketDocument;
import com.flashseats.shared.ticket.TicketPdfRenderer;
import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.stereotype.Service;

/**
 * Hands a buyer the ticket they paid for, without going through their inbox (ADR-050).
 *
 * <p><strong>Why this exists.</strong> The PDF used to be reachable only as an email attachment. The
 * address is collected once, in the checkout body, and never verified — so a typo meant the ticket
 * went to a stranger or bounced, the buyer held a valid receipt and a 90-day token and still could
 * not obtain what they had paid for, and even the operator resend replayed the same outbox payload
 * to the same wrong address. Every other failure in this system has a recovery path; this one had
 * none, and it ended with a paying buyer holding nothing.
 *
 * <p><strong>Two beans, not one, and that is the whole shape of this class.</strong> Reading and
 * authorising is transactional and lives in {@link OrderQueryService}; rendering is CPU work and must
 * not happen with a pooled connection open (ADR-023). Putting both in one class and annotating the
 * read would have been silently wrong: Spring's transaction proxy does not intercept
 * self-invocation, so the read would have run with <em>no transaction at all</em> — the same reason
 * {@code CheckoutService} and {@code OrderCommitService} are separate types.
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
