package com.flashseats.shared.ticket;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.text.Normalizer;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
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
 *
 * <p>The layout is a print-at-home ticket (ADR-081): a bordered ticket with a tear-off stub, wholly
 * inside the top third of the page so a letter fold never crosses it, and the order number large on
 * the stub because it is what door staff type in. Dates are formatted in English and UTC, so the
 * bytes do not depend on which replica's locale rendered them.
 */
@Slf4j
@Component
public class TicketPdfRenderer {

    private static final DateTimeFormatter DAY =
            DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter TIME =
            DateTimeFormatter.ofPattern("HH:mm 'UTC'", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    /** What the standard-14 fonts can actually draw. */
    private static final Charset WIN_ANSI = Charset.forName("windows-1252");

    /** Stands in for a character the font cannot render, so the line still reads. */
    private static final char REPLACEMENT = '?';

    private static final String ELLIPSIS = "…";

    // The client's light palette (frontend/src/app/theme.ts), so the ticket looks like the site.
    private static final Color BRAND = new Color(0x4338CA);
    private static final Color INK = new Color(0x151826);
    private static final Color MUTED = new Color(0x4B5165);
    private static final Color LABEL = new Color(0x6B7186);
    private static final Color RULE = new Color(0xC9CEDC);

    private static final float PAGE_WIDTH = PDRectangle.A4.getWidth();
    private static final float PAGE_HEIGHT = PDRectangle.A4.getHeight();
    private static final float MARGIN = 40f;
    private static final float CONTENT_WIDTH = PAGE_WIDTH - 2 * MARGIN;

    /** The ticket ends above the first letter fold, at a third of the page. */
    private static final float TICKET_TOP = PAGE_HEIGHT - MARGIN;
    private static final float TICKET_HEIGHT = 226f;
    private static final float TICKET_BOTTOM = TICKET_TOP - TICKET_HEIGHT;
    private static final float BAND_HEIGHT = 30f;
    private static final float STUB_WIDTH = 150f;
    private static final float PERFORATION_X = MARGIN + CONTENT_WIDTH - STUB_WIDTH;
    private static final float PAD = 20f;
    private static final float CORNER = 10f;
    private static final float NOTCH = 9f;

    private static final float MAIN_X = MARGIN + PAD;
    private static final float MAIN_WIDTH = PERFORATION_X - PAD - MAIN_X;
    private static final float SECOND_COLUMN_X = MAIN_X + 190f;
    private static final float STUB_X = PERFORATION_X + PAD;
    private static final float STUB_INNER_WIDTH = STUB_WIDTH - 2 * PAD;

    private static final float TITLE_MAX = 24f;
    private static final float TITLE_MIN = 17f;
    private static final float TITLE_WRAPPED = 18f;

    public byte[] render(TicketDocument ticket) throws IOException {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Fonts fonts = new Fonts(
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA),
                    new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD));
            Text text = Text.of(ticket);
            int total = ticket.seats().size();
            for (int i = 0; i < total; i++) {
                document.addPage(renderTicket(document, fonts, text, ticket.seats().get(i), i + 1, total));
            }
            document.save(out);
            return out.toByteArray();
        }
    }

    private record Fonts(PDFont regular, PDFont bold) {}

    /** The ticket's strings, each sanitised once, so a degraded title is logged once per render. */
    private record Text(String order, String title, String venue, String day, String time) {
        static Text of(TicketDocument ticket) {
            return new Text(
                    drawable(ticket.orderNumber()),
                    drawable(ticket.eventTitle()),
                    drawable(ticket.venueName()),
                    DAY.format(ticket.eventStartTime()),
                    TIME.format(ticket.eventStartTime()));
        }
    }

    private PDPage renderTicket(
            PDDocument document, Fonts fonts, Text text, TicketDocument.Seat seat, int index, int total)
            throws IOException {

        PDPage page = new PDPage(PDRectangle.A4);
        String tier = drawable(seat.tierName());
        String admits = seat.quantity() == 1 ? "1 person" : seat.quantity() + " people";
        String counter = "TICKET " + index + " OF " + total;

        try (PDPageContentStream content = new PDPageContentStream(document, page)) {
            drawFrame(content);

            // Band: brand on the left, which ticket of the order this is on the right.
            write(content, fonts.bold(), 11, Color.WHITE, 2f, MAIN_X, TICKET_TOP - 19, "FLASHSEATS");
            float counterWidth = width(fonts.bold(), 8.5f, 1f, counter);
            write(content, fonts.bold(), 8.5f, Color.WHITE, 1f,
                    MARGIN + CONTENT_WIDTH - PAD - counterWidth, TICKET_TOP - 18.5f, counter);

            // Main panel: what, where, when, then the fields staff look for.
            float y = TICKET_TOP - BAND_HEIGHT - 32;
            for (Line line : fitTitle(fonts.bold(), text.title())) {
                write(content, fonts.bold(), line.size(), INK, 0, MAIN_X, y, line.text());
                y -= line.size() + 4;
            }
            y -= 2;
            write(content, fonts.regular(), 12, MUTED, 0, MAIN_X, y,
                    ellipsize(fonts.regular(), 12, text.venue(), MAIN_WIDTH));

            y -= 34;
            field(content, fonts, "DATE", text.day(), MAIN_X, y, SECOND_COLUMN_X - MAIN_X - 12);
            field(content, fonts, "STARTS", text.time(), SECOND_COLUMN_X, y,
                    MAIN_X + MAIN_WIDTH - SECOND_COLUMN_X);
            y -= 40;
            field(content, fonts, "TIER", tier, MAIN_X, y, SECOND_COLUMN_X - MAIN_X - 12);
            field(content, fonts, "ADMITS", admits, SECOND_COLUMN_X, y,
                    MAIN_X + MAIN_WIDTH - SECOND_COLUMN_X);

            // Stub: the admit count and the order number, large, as on a paper ticket.
            float s = TICKET_TOP - BAND_HEIGHT - 26;
            label(content, fonts, "ADMIT", STUB_X, s);
            write(content, fonts.bold(), 44, BRAND, 0, STUB_X, s - 44, String.valueOf(seat.quantity()));
            float stubLine = s - 64;
            for (String line : wrap(fonts.bold(), 12, tier, STUB_INNER_WIDTH, 2)) {
                write(content, fonts.bold(), 12, INK, 0, STUB_X, stubLine, line);
                stubLine -= 15;
            }
            field(content, fonts, "ORDER", text.order(), STUB_X, s - 110, STUB_INNER_WIDTH, 15);

            drawNotes(content, fonts, text, tier, seat.quantity(), total);

            String footer = "FlashSeats  ·  Order " + text.order() + "  ·  Ticket " + index + " of " + total;
            write(content, fonts.regular(), 8, LABEL, 0, MARGIN, MARGIN - 12, footer);
        }
        return page;
    }

    /** The ticket outline: rounded border, brand band, and a perforation with a notch at each end. */
    private void drawFrame(PDPageContentStream content) throws IOException {
        content.saveGraphicsState();
        roundedRect(content, MARGIN, TICKET_BOTTOM, CONTENT_WIDTH, TICKET_HEIGHT, CORNER);
        content.clip();

        content.setNonStrokingColor(BRAND);
        content.addRect(MARGIN, TICKET_TOP - BAND_HEIGHT, CONTENT_WIDTH, BAND_HEIGHT);
        content.fill();

        // Inside the clip, so only the half of each notch that bites into the ticket is drawn.
        content.setNonStrokingColor(Color.WHITE);
        content.setStrokingColor(RULE);
        content.setLineWidth(1f);
        for (float cy : new float[] {TICKET_TOP, TICKET_BOTTOM}) {
            circle(content, PERFORATION_X, cy, NOTCH);
            content.fillAndStroke();
        }
        content.restoreGraphicsState();

        content.setStrokingColor(RULE);
        content.setLineWidth(1f);
        roundedRect(content, MARGIN, TICKET_BOTTOM, CONTENT_WIDTH, TICKET_HEIGHT, CORNER);
        content.stroke();

        content.saveGraphicsState();
        content.setLineDashPattern(new float[] {3f, 3f}, 0f);
        content.moveTo(PERFORATION_X, TICKET_BOTTOM + NOTCH + 3);
        content.lineTo(PERFORATION_X, TICKET_TOP - BAND_HEIGHT - 3);
        content.stroke();
        content.restoreGraphicsState();
    }

    /** Below the ticket: what a buyer needs to know before the day, in plain sentences. */
    private void drawNotes(
            PDPageContentStream content, Fonts fonts, Text text, String tier, int quantity, int total)
            throws IOException {

        float y = TICKET_BOTTOM - 44;
        write(content, fonts.bold(), 13, INK, 0, MARGIN, y, "Before you go");
        y -= 8;
        content.setStrokingColor(RULE);
        content.setLineWidth(0.75f);
        content.moveTo(MARGIN, y);
        content.lineTo(MARGIN + CONTENT_WIDTH, y);
        content.stroke();
        y -= 20;

        String scope = "This page is your " + tier + " ticket and admits " + quantity
                + (quantity == 1 ? " person." : " people.");
        if (total > 1) {
            scope += " Order " + text.order() + " has " + total + " tickets, one per page: bring every page.";
        }
        List<String> notes = List.of(
                scope,
                "Show it at the entrance, printed or on your phone. If you print it, keep the fold"
                        + " away from the ticket.",
                "Keep it to yourself. Anyone holding a copy can present it, so do not share or post it.",
                "The time is shown in UTC. Check the event's local start time before you travel.",
                "Lost this file? Download it again from your order confirmation page.");

        float bulletIndent = 14f;
        for (String note : notes) {
            write(content, fonts.bold(), 10.5f, BRAND, 0, MARGIN, y, "•");
            for (String line : wrap(fonts.regular(), 10.5f, note, CONTENT_WIDTH - bulletIndent, 3)) {
                write(content, fonts.regular(), 10.5f, MUTED, 0, MARGIN + bulletIndent, y, line);
                y -= 15;
            }
            y -= 5;
        }
    }

    private void field(
            PDPageContentStream content, Fonts fonts, String name, String value, float x, float y, float maxWidth)
            throws IOException {
        field(content, fonts, name, value, x, y, maxWidth, 12);
    }

    /** A small uppercase label over a bold value, the way a paper ticket lays out its fields. */
    private void field(
            PDPageContentStream content,
            Fonts fonts,
            String name,
            String value,
            float x,
            float y,
            float maxWidth,
            float valueSize)
            throws IOException {
        label(content, fonts, name, x, y);
        write(content, fonts.bold(), valueSize, INK, 0, x, y - valueSize - 4,
                ellipsize(fonts.bold(), valueSize, value, maxWidth));
    }

    private void label(PDPageContentStream content, Fonts fonts, String name, float x, float y)
            throws IOException {
        write(content, fonts.bold(), 7.5f, LABEL, 1.2f, x, y, name);
    }

    /** Draws one already-sanitised string at a baseline. */
    private void write(
            PDPageContentStream content,
            PDFont font,
            float size,
            Color color,
            float spacing,
            float x,
            float y,
            String text)
            throws IOException {
        if (text.isEmpty()) {
            return;
        }
        content.beginText();
        content.setFont(font, size);
        content.setNonStrokingColor(color);
        content.setCharacterSpacing(spacing);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }

    private record Line(String text, float size) {}

    /**
     * The largest size at which the title fits one line; failing that, two lines at a fixed size with
     * the rest elided. An operator's title has no length limit the page can rely on.
     */
    private List<Line> fitTitle(PDFont font, String title) throws IOException {
        for (float size = TITLE_MAX; size >= TITLE_MIN; size -= 1f) {
            if (width(font, size, 0, title) <= MAIN_WIDTH) {
                return List.of(new Line(title, size));
            }
        }
        List<Line> lines = new ArrayList<>();
        for (String line : wrap(font, TITLE_WRAPPED, title, MAIN_WIDTH, 2)) {
            lines.add(new Line(line, TITLE_WRAPPED));
        }
        return lines;
    }

    /** Greedy word wrap; anything past {@code maxLines} is folded into an elided last line. */
    private static List<String> wrap(PDFont font, float size, String text, float maxWidth, int maxLines)
            throws IOException {
        List<String> lines = new ArrayList<>();
        String current = "";
        for (String word : text.strip().split("\\s+")) {
            String candidate = current.isEmpty() ? word : current + " " + word;
            if (current.isEmpty() || width(font, size, 0, candidate) <= maxWidth) {
                current = candidate;
            } else {
                lines.add(current);
                current = word;
            }
        }
        if (!current.isEmpty()) {
            lines.add(current);
        }
        if (lines.size() > maxLines) {
            String rest = String.join(" ", lines.subList(maxLines - 1, lines.size()));
            lines = new ArrayList<>(lines.subList(0, maxLines - 1));
            lines.add(rest);
        }
        List<String> fitted = new ArrayList<>(lines.size());
        for (String line : lines) {
            fitted.add(ellipsize(font, size, line, maxWidth));
        }
        return fitted;
    }

    private static String ellipsize(PDFont font, float size, String text, float maxWidth) throws IOException {
        if (width(font, size, 0, text) <= maxWidth) {
            return text;
        }
        String cut = text;
        while (!cut.isEmpty() && width(font, size, 0, cut + ELLIPSIS) > maxWidth) {
            cut = cut.substring(0, cut.length() - 1);
        }
        return cut.stripTrailing() + ELLIPSIS;
    }

    private static float width(PDFont font, float size, float spacing, String text) throws IOException {
        return font.getStringWidth(text) / 1000f * size + spacing * text.length();
    }

    private static void roundedRect(PDPageContentStream content, float x, float y, float w, float h, float r)
            throws IOException {
        float k = r * 0.5523f; // Bezier handle length for a quarter circle
        content.moveTo(x + r, y);
        content.lineTo(x + w - r, y);
        content.curveTo(x + w - r + k, y, x + w, y + r - k, x + w, y + r);
        content.lineTo(x + w, y + h - r);
        content.curveTo(x + w, y + h - r + k, x + w - r + k, y + h, x + w - r, y + h);
        content.lineTo(x + r, y + h);
        content.curveTo(x + r - k, y + h, x, y + h - r + k, x, y + h - r);
        content.lineTo(x, y + r);
        content.curveTo(x, y + r - k, x + r - k, y, x + r, y);
        content.closePath();
    }

    private static void circle(PDPageContentStream content, float cx, float cy, float r) throws IOException {
        float k = r * 0.5523f;
        content.moveTo(cx + r, cy);
        content.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r);
        content.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy);
        content.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r);
        content.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy);
        content.closePath();
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
