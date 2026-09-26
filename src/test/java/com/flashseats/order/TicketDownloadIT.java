package com.flashseats.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.flashseats.app.support.BuyerSession;
import com.flashseats.app.support.IntegrationTest;
import com.flashseats.app.support.SaleFixture;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * A buyer can always obtain the ticket they paid for (ADR-050).
 */
@DisplayName("A paid-for ticket is always retrievable")
class TicketDownloadIT extends IntegrationTest {

    private static final Duration PATIENCE = Duration.ofSeconds(15);

    private static final Map<String, String> ACCEPT_PDF =
            Map.of("Accept", "application/pdf, application/problem+json");

    @LocalServerPort
    private int port;

    @Autowired
    private SaleFixture fixture;

    @Autowired
    private JdbcTemplate jdbc;

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

        BuyerSession stranger = new BuyerSession(port);
        var pdf = stranger.get(
                "/orders/" + orderNumber + "/ticket.pdf?receiptToken=" + receiptToken,
                ACCEPT_PDF);

        assertThat(pdf.status()).isEqualTo(200);
        assertThat(pdf.rawBody()).startsWith("%PDF");
    }

    @Test
    @DisplayName("Neither a session nor a token gets 404")
    void unauthorisedCallerCannotTellTheOrderExists() {
        BuyerSession buyer = admittedBuyer();
        String orderNumber = buy(buyer, 1).text("orderNumber");

        BuyerSession stranger = new BuyerSession(port);
        var refused = stranger.get("/orders/" + orderNumber + "/ticket.pdf", ACCEPT_PDF);

        assertThat(refused.status()).isEqualTo(404);
        assertThat(refused.errorCode()).isEqualTo("ORDER_NOT_FOUND");
    }

    @Test
    @DisplayName("An order that never confirmed has no ticket")
    void unconfirmedOrderHasNoTicket() {
        BuyerSession buyer = admittedBuyer();
        String holdToken = reserve(buyer, 1);

        fixture.strandPendingOrder(holdToken, 7_500);
        String orderNumber = fixture.orderNumberFor(holdToken);

        var refused = buyer.get("/orders/" + orderNumber + "/ticket.pdf", ACCEPT_PDF);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.errorCode()).isEqualTo("TICKET_NOT_AVAILABLE");
        assertThat(refused.text("orderStatus")).isEqualTo("PENDING");
        assertThat(refused.json().get("retryable").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("A refunded order has no ticket")
    void refundedOrderHasNoTicket() {
        BuyerSession buyer = admittedBuyer();
        String orderNumber = buy(buyer, 1).text("orderNumber");

        jdbc.update(
                "UPDATE orders SET status = 'REFUNDED', failure_reason = 'test refund' WHERE order_number = ?",
                orderNumber);

        var refused = buyer.get("/orders/" + orderNumber + "/ticket.pdf", ACCEPT_PDF);

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.errorCode()).isEqualTo("TICKET_NOT_AVAILABLE");
        assertThat(refused.text("orderStatus")).isEqualTo("REFUNDED");
        assertThat(refused.json().get("retryable").asBoolean()).isFalse();
    }

    private BuyerSession admittedBuyer() {
        BuyerSession buyer = new BuyerSession(port);
        buyer.get("/events/" + eventId);
        buyer.post("/queue/join", Map.of("eventId", eventId));

        String passToken = await().atMost(PATIENCE)
                .until(
                        () -> buyer.get("/queue/status?eventId=" + eventId).text("passToken"),
                        token -> token != null);

        admissionToken = buyer
                .post(
                        "/queue/admit",
                        Map.of("eventId", eventId),
                        Map.of("X-Queue-Pass-Token", passToken))
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
