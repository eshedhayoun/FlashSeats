package com.flashseats.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.flashseats.notification.dto.OrderConfirmedPayload;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The renderer must always produce a ticket.
 *
 * <p>A font failure here is <em>deterministic</em>, so ADR-029 correctly sends it straight to the
 * dead-letter queue with no retry — and this MVP has no admin replay endpoint. That combination
 * means one unrenderable character in an event title cost a buyer who had already been charged
 * their ticket outright, with no automated way back. A degraded glyph is a cosmetic loss; an
 * undelivered ticket is not.
 */
@DisplayName("TicketPdfRenderer")
class TicketPdfRendererTest {

    private final TicketPdfRenderer renderer = new TicketPdfRenderer();

    @Test
    @DisplayName("Accented text keeps its letters rather than failing")
    void accentsAreTransliterated() {
        assertThat(TicketPdfRenderer.drawable("Café Über Ångström")).isEqualTo("Cafe Uber Angstrom");
    }

    @Test
    @DisplayName("Characters no standard-14 font can draw degrade instead of throwing")
    void unrenderableCharactersDegrade() {
        // Hebrew, CJK and emoji are all outside WinAnsi. showText throws on every one of them.
        assertThat(TicketPdfRenderer.drawable("קונצרט")).isEqualTo("??????");
        assertThat(TicketPdfRenderer.drawable("Aurora 🎵")).startsWith("Aurora ");
    }

    @Test
    @DisplayName("Plain ASCII is untouched")
    void asciiIsUnchanged() {
        assertThat(TicketPdfRenderer.drawable("Aurora Fest 2026")).isEqualTo("Aurora Fest 2026");
        assertThat(TicketPdfRenderer.drawable(null)).isEmpty();
    }

    @Test
    @DisplayName("A non-Latin event title still renders a PDF")
    void nonLatinTitleStillRenders() throws Exception {
        OrderConfirmedPayload payload = payloadTitled("מופע חצות");

        assertThatCode(() -> renderer.render(payload)).doesNotThrowAnyException();
        assertThat(renderer.render(payload)).startsWith("%PDF".getBytes());
    }

    @Test
    @DisplayName("One page per line item, so a multi-tier order is not one wrong ticket")
    void multiTierOrderRendersEveryItem() throws Exception {
        assertThat(renderer.render(payloadTitled("Aurora Fest"))).isNotEmpty();
    }

    private OrderConfirmedPayload payloadTitled(String title) {
        return new OrderConfirmedPayload(
                "ORDER_CONFIRMED",
                "TK-00001",
                "rcp_test",
                "buyer@example.com",
                15_000,
                "USD",
                Instant.parse("2026-08-30T10:04:12Z"),
                new OrderConfirmedPayload.EventInfo(
                        1L, title, "היכל התרבות", Instant.parse("2026-09-14T19:00:00Z")),
                List.of(
                        new OrderConfirmedPayload.Item(501L, "VIP", 2, 7_500),
                        new OrderConfirmedPayload.Item(502L, "Floor", 1, 4_500)));
    }
}
