package com.flashseats.notification.service;

import com.flashseats.notification.dto.OrderConfirmedPayload;
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
 *
 * <p><strong>Which is exactly why every string is sanitised before it is drawn.</strong> The
 * standard-14 fonts encode WinAnsi, and {@code showText} throws on any character outside it — so a
 * Hebrew, Cyrillic, CJK or emoji event title turned a <em>paid</em> order into a dead letter with no
 * retry and, in this MVP, no admin replay endpoint to recover it. The buyer simply never received
 * the ticket they had been charged for. Degrading the glyph is strictly better than losing the
 * ticket; carrying a Unicode TTF is the real fix and belongs with the rest of the Stage 4 work.
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
        content.showText(drawable(text));
        content.endText();
        return y - (size + 6);
    }

    /**
     * Reduces operator-supplied text to something the standard-14 fonts can actually draw.
     *
     * <p>Two steps, in order. Normalising to NFD and dropping the combining marks turns {@code "é"}
     * into {@code "e"} and {@code "Ø"} into {@code "O"} — an accent lost, but the word still
     * readable, which is what matters on a ticket someone holds at a door. Whatever still cannot be
     * encoded becomes {@code '?'}.
     *
     * <p><strong>It never throws.</strong> That is the whole point: {@code showText} does, and
     * because a font failure is deterministic, ADR-029 correctly sends it straight to the DLQ with
     * no retry — so one unrenderable character in an event title used to cost a paying buyer their
     * ticket outright, with no automated path back. A degraded glyph is a cosmetic loss; an
     * undelivered ticket is not.
     */
    static String drawable(String text) {
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
