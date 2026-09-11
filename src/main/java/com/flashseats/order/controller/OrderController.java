package com.flashseats.order.controller;

import com.flashseats.order.dto.CheckoutRequest;
import com.flashseats.order.dto.OrderReceiptResponse;
import com.flashseats.order.service.CheckoutOutcome;
import com.flashseats.order.service.CheckoutService;
import com.flashseats.order.service.OrderQueryService;
import com.flashseats.shared.identity.SessionId;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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

    public OrderController(CheckoutService checkout, OrderQueryService orders) {
        this.checkout = checkout;
        this.orders = orders;
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
}
