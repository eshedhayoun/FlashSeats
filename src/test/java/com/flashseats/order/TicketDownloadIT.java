package com.flashseats.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flashseats.flashseats.support.BuyerSession;
import com.flashseats.flashseats.support.IntegrationTest;
import com.flashseats.flashseats.support.SaleFixture;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * A buyer can always obtain the ticket they paid for (ADR-050).
 *
 * <p>The gap this closes: the PDF was reachable only as an email attachment, and the address is
 * taken from the checkout body and never verified. A typo sent the ticket to a stranger or bounced
 * it, the buyer held a valid receipt and a 90-day token and could still not obtain what they had
 * paid for, and the operator resend replayed the same payload to the same wrong address. Every other
 * failure in this system has a recovery path; this one ended with a paying buyer holding nothing.
 */
@DisplayName("A paid-for ticket is always retrievable")
class TicketDownloadIT extends IntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(15);

    /**
     * What a client downloading a ticket actually sends: the PDF it wants, <em>and</em> the error
     * shape it must still be able to read. An {@code Accept} of only {@code application/pdf} makes
     * every failure on this endpoint unnegotiable, which is a mistake worth not baking into the test
     * that guards it.
     */
    private static final Map<String, String> ACCEPT_PDF =
            Map.of("Accept", "application/pdf, application/problem+json");

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    private long eventId;
    private long tierId;
    private String admissionToken;

    @BeforeEach
    void seedSale() {
        fixture.reset();
        eventId = fixture.openEvent("Aurora Fest");
        tierId = fixture.tier(eventId, "VIP", 7_500, 20);
    }

    @Test
    @DisplayName("The buyer's own session downloads the ticket as a PDF")
    void ownSessionDownloadsTicket() {
        BuyerSession buyer = admittedBuyer();
        var receipt = buy(buyer, 2);
        String orderNumber = receipt.text("orderNumber");

        var pdf = buyer.get("/orders/" + orderNumber + "/ticket.pdf", ACCEPT_PDF);

        assertThat(pdf.status()).isEqualTo(200);
        assertThat(pdf.rawBody()).startsWith("%PDF");
    }

    @Test
    @DisplayName("The receipt token works from a browser that has never seen this sale")
    void receiptTokenWorksWithoutACookie() {
        BuyerSession buyer = admittedBuyer();
        var receipt = buy(buyer, 1);
        String orderNumber = receipt.text("orderNumber");
        String receiptToken = receipt.text("receiptToken");

        // A clean jar: no fsid cookie at all. This is the path from the confirmation email, weeks
        // later, on a different device — and the path that makes a mistyped address survivable.
        BuyerSession stranger = new BuyerSession(port);
        var pdf = stranger.get(
                "/orders/" + orderNumber + "/ticket.pdf?receiptToken=" + receiptToken, ACCEPT_PDF);

        assertThat(pdf.status()).isEqualTo(200);
        assertThat(pdf.rawBody()).startsWith("%PDF");
    }

    @Test
    @DisplayName("Neither a session nor a token gets 404 — never 403, so order numbers stay unguessable")
    void unauthorisedCallerCannotTellTheOrderExists() {
        BuyerSession buyer = admittedBuyer();
        String orderNumber = buy(buyer, 1).text("orderNumber");

        BuyerSession stranger = new BuyerSession(port);
        var refused = stranger.get("/orders/" + orderNumber + "/ticket.pdf", ACCEPT_PDF);

        // 404, not 403. Confirming the order exists is itself a leak: a 403 on a real order number
        // and a 404 on a fake one is an oracle for enumerating them (ADR-010).
        assertThat(refused.status()).isEqualTo(404);
        assertThat(refused.errorCode()).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("An order that never confirmed has no ticket, and says which kind of no it is")
    void unconfirmedOrderHasNoTicket() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        // Committed as PENDING before the charge, exactly as a crash between the two would leave it.
        fixture.strandPendingOrder(holdToken);
        String orderNumber = fixture.orderNumberFor(holdToken);

        var refused = buyer.get("/orders/" + orderNumber + "/ticket.pdf", ACCEPT_PDF);

        // 409 and not 404, because this caller has already proved the order is theirs — so it can
        // afford to say why. Rendering here would mint a document indistinguishable from a real
        // ticket for a purchase that never completed.
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.errorCode()).isEqualTo("TICKET_NOT_AVAILABLE");
        assertThat(refused.text("orderStatus")).isEqualTo("PENDING");
        // PENDING is in-flight, so waiting is the right advice (ADR-034). Every other status is
        // terminal and it is not.
        assertThat(refused.json().get("retryable").asBoolean()).isTrue();
    }

    // ----------------------------------------------------------------- helpers

    private BuyerSession admittedBuyer() {
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);
        buyer.post("/queue/join", Map.of("eventId", eventId));

        String passToken = await().atMost(PATIENCE)
                .until(() -> buyer.get("/queue/status?eventId=" + eventId).text("passToken"),
                        token -> token != null);
        admissionToken = buyer
                .post("/queue/admit", Map.of("eventId", eventId), Map.of("X-Queue-Pass-Token", passToken))
                .text("admissionToken");
        return buyer;
    }

    private String reserve(BuyerSession buyer, int quantity) {
        return buyer.post(
                        "/holds",
                        Map.of("eventId", eventId, "tierId", tierId, "quantity", quantity),
                        Map.of("X-Admission-Token", admissionToken))
                .text("holdToken");
    }

    private BuyerSession.Response buy(BuyerSession buyer, int quantity) {
        String holdToken = reserve(buyer, quantity);
        var receipt = buyer.post(
                "/orders/checkout",
                Map.of(
                        "holdToken", holdToken,
                        "userEmail", "buyer@example.com",
                        "paymentMethodId", "pm_card_visa",
                        "idempotencyKey", "ticket-" + holdToken));
        assertThat(receipt.status()).isEqualTo(201);
        return receipt;
    }
}
