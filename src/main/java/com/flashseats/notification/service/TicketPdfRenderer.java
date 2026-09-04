package com.flashseats.notification.service;

import com.flashseats.notification.dto.OrderConfirmedPayload;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

/**
 * Renders the ticket PDF in memory.
 *
 * <p><strong>One page per line item</strong>, because a ticket is a thing a person holds at a door.
 * An earlier payload design carried a single flat tier and quantity, which would have produced one
 * wrong page for any multi-tier order (ADR-015).
 *
 * <p>Uses the standard-14 fonts only — no font loading, no glyph lookups, nothing that can fail
 * differently on a different machine. A render failure here is deterministic and must not be
 * retried: it would fail identically three times and reach the same dead-letter queue 2.5 minutes
 * later (ADR-029).
 */
@Component
public class TicketPdfRenderer {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'at' HH:mm").withZone(ZoneOffset.UTC);

    private static final float MARGIN = 56f;
    private static final float TITLE_SIZE = 22f;
    private static final float BODY_SIZE = 12f;

    public byte[] render(OrderConfirmedPayload payload) throws IOException {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            for (OrderConfirmedPayload.Item item : payload.items()) {
                document.addPage(renderTicket(document, payload, item));
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private PDPage renderTicket(
            PDDocument document, OrderConfirmedPayload payload, OrderConfirmedPayload.Item item)
            throws IOException {

        PDPage page = new PDPage(PDRectangle.A4);
        float top = PDRectangle.A4.getHeight() - MARGIN;

        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            float y = top;

            y = write(content, payload.event().title(), TITLE_SIZE, true, MARGIN, y);
            y -= 10;
            y = write(content, payload.event().venueName(), BODY_SIZE + 2, false, MARGIN, y);
            y = write(content, DATE.format(payload.event().startTime()), BODY_SIZE, false, MARGIN, y);

            y -= 28;
            y = write(content, item.tierName(), TITLE_SIZE - 4, true, MARGIN, y);
            y = write(content, "Admits " + item.quantity(), BODY_SIZE, false, MARGIN, y);

            y -= 28;
            y = write(content, "Order " + payload.orderNumber(), BODY_SIZE + 4, true, MARGIN, y);
            write(content, "Present this page at the door.", BODY_SIZE, false, MARGIN, y - 4);
        }
        return page;
    }

    /** Writes one line and returns the baseline for the next. */
    private float write(
            PDPageContentStream content, String text, float size, boolean bold, float x, float y)
            throws IOException {

        content.beginText();
        content.setFont(
                new PDType1Font(
                        bold ? Standard14Fonts.FontName.HELVETICA_BOLD : Standard14Fonts.FontName.HELVETICA),
                size);
        content.newLineAtOffset(x, y);
        content.showText(text == null ? "" : text);
        content.endText();
        return y - (size + 6);
    }
}
