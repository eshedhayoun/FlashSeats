package com.flashseats.shared.ticket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.text.Normalizer;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.springframework.stereotype.Component;

/**
 * Renders the ticket PDF in memory, one page per line item (ADR-015). It is in the kernel because it
 * is a pure function (ADR-050): {@code notification} emails it and {@code order} serves it as a
 * download, byte-identical by construction.
 *
 * <p>Standard-14 fonts only, so a render failure is deterministic and never retried (ADR-029).
 * Those fonts encode WinAnsi and {@code showText} throws outside it, so every string is sanitised
 * first. A Hebrew title must not cost a paid buyer their ticket. A Unicode TTF is the real fix.
 */
@Slf4j
@Component
public class TicketPdfRenderer {

    private static final DateTimeFormatter DATE =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy 'at' HH:mm").withZone(ZoneOffset.UTC);

    /** What the standard-14 fonts can actually draw. */
    private static final Charset WIN_ANSI = Charset.forName("windows-1252");

    /** Stands in for a character the font cannot render, so the line still reads. */
    private static final char REPLACEMENT = '?';

    private static final float MARGIN = 56f;
    private static final float TITLE_SIZE = 22f;
    private static final float BODY_SIZE = 12f;

    public byte[] render(TicketDocument ticket) throws IOException {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            for (TicketDocument.Seat seat : ticket.seats()) {
                document.addPage(renderTicket(document, ticket, seat));
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private PDPage renderTicket(PDDocument document, TicketDocument ticket, TicketDocument.Seat seat)
            throws IOException {

        PDPage page = new PDPage(PDRectangle.A4);
        float top = PDRectangle.A4.getHeight() - MARGIN;

        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            float y = top;

            y = write(content, ticket.eventTitle(), TITLE_SIZE, true, MARGIN, y);
            y -= 10;
            y = write(content, ticket.venueName(), BODY_SIZE + 2, false, MARGIN, y);
            y = write(content, DATE.format(ticket.eventStartTime()), BODY_SIZE, false, MARGIN, y);

            y -= 28;
            y = write(content, seat.tierName(), TITLE_SIZE - 4, true, MARGIN, y);
            y = write(content, "Admits " + seat.quantity(), BODY_SIZE, false, MARGIN, y);

            y -= 28;
            y = write(content, "Order " + ticket.orderNumber(), BODY_SIZE + 4, true, MARGIN, y);
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
        content.showText(drawable(text));
        content.endText();
        return y - (size + 6);
    }

    /**
     * Reduces operator-supplied text to what the standard-14 fonts can draw: NFD-normalise and drop
     * combining marks ({@code "é"} → {@code "e"}), then {@code '?'} for anything left. <strong>It never
     * throws</strong>, because a font failure is deterministic and would dead-letter a paid ticket
     * (ADR-029). A degraded glyph is cosmetic; a lost ticket is not.
     */
    public static String drawable(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");

        // One encoder per call, not per character: CharsetEncoder is stateful and not thread-safe,
        // so it cannot be a shared constant, but allocating one per char was pure waste.
        CharsetEncoder encoder = WIN_ANSI.newEncoder();
        StringBuilder safe = new StringBuilder(decomposed.length());
        boolean degraded = false;
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (encoder.canEncode(c)) {
                safe.append(c);
            } else {
                safe.append(REPLACEMENT);
                degraded = true;
            }
        }
        if (degraded) {
            log.warn(
                    "Ticket text contained characters the standard-14 fonts cannot render; "
                            + "they were replaced. Embed a Unicode font to carry them properly.");
        }
        return safe.toString();
    }
}
