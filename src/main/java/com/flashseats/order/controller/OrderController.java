package com.flashseats.order.controller;

import com.flashseats.order.dto.CheckoutRequest;
import com.flashseats.order.dto.OrderReceiptResponse;
import com.flashseats.order.service.CheckoutOutcome;
import com.flashseats.order.service.CheckoutService;
import com.flashseats.order.service.OrderQueryService;
import com.flashseats.order.service.TicketDownloadService;
import com.flashseats.shared.identity.SessionId;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Checkout and receipts. */
@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    private final CheckoutService checkout;
    private final OrderQueryService orders;
    private final TicketDownloadService tickets;

    public OrderController(
            CheckoutService checkout, OrderQueryService orders, TicketDownloadService tickets) {
        this.checkout = checkout;
        this.orders = orders;
        this.tickets = tickets;
    }

    /**
     * Buys the seats held under {@code holdToken}.
     *
     * <p>{@code 201} for a new purchase, {@code 200} when an already-completed checkout is replayed —
     * a client that submits twice gets its receipt back rather than an error, because the operation
     * genuinely completed (global standards §3).
     */
    @PostMapping("/checkout")
    public ResponseEntity<OrderReceiptResponse> checkout(
            @Valid @RequestBody CheckoutRequest request, SessionId session) {

        CheckoutOutcome outcome = checkout.checkout(session.value(), request);
        return ResponseEntity.status(outcome.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                .body(outcome.receipt());
    }

    /**
     * The receipt. Requires a matching session cookie <strong>or</strong> a valid
     * {@code receiptToken} — the order number alone authorises nothing (ADR-010).
     */
    @GetMapping("/{orderNumber}")
    public OrderReceiptResponse get(
            @PathVariable String orderNumber,
            @RequestParam(required = false) String receiptToken,
            SessionId session) {
        return orders.readAuthorised(orderNumber, session.value(), receiptToken);
    }

    /**
     * The ticket itself (ADR-050).
     *
     * <p>Authorised exactly like the receipt above — session cookie <strong>or</strong>
     * {@code receiptToken} — because it is the same fact about the same order. Before this existed
     * the PDF was reachable only as an email attachment, so an address typed wrong at checkout left
     * a paying buyer with no way to obtain it and the operator resend replaying to the same wrong
     * address.
     *
     * <p>{@code inline}, not {@code attachment}: a buyer on a phone at a venue door wants the ticket
     * on screen, not in a downloads folder they then have to find.
     *
     * <p><strong>No {@code produces} on the mapping</strong>, deliberately. Constraining it to
     * {@code application/pdf} also constrains the <em>error</em> responses, so a {@code 404} or a
     * {@code TICKET_NOT_AVAILABLE} could not be rendered as {@code application/problem+json} and came
     * back as an unnegotiable {@code 406} instead — turning every failure on this endpoint into a
     * response with no registry {@code code}. The content type belongs on the successful body, which
     * is where it is set.
     */
    @GetMapping("/{orderNumber}/ticket.pdf")
    public ResponseEntity<byte[]> ticket(
            @PathVariable String orderNumber,
            @RequestParam(required = false) String receiptToken,
            SessionId session) {

        byte[] pdf = tickets.render(orderNumber, session.value(), receiptToken);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        "inline; filename=\"" + orderNumber + ".pdf\"")
                // A ticket is a bearer document and the URL can carry a 90-day token. Keep it out of
                // shared caches entirely.
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(pdf);
    }
}
