package com.flashseats.order.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.hold.exception.HoldExpiredException;
import com.flashseats.hold.facade.HoldFacade;
import com.flashseats.hold.facade.HoldSummary;
import com.flashseats.order.model.Order;
import com.flashseats.order.model.OrderStatus;
import com.flashseats.order.repository.OrderRepository;
import com.flashseats.payment.event.PaymentSettledEvent;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which way the webhook routes a lost hold. That a refund claim cannot undo a confirmation is
 * proven against PostgreSQL in {@code SettlementArbiterIT} (ADR-064).
 */
@DisplayName("The webhook lets the order row decide when the hold is gone")
class PaymentSettlementServiceTest {

    private static final String HOLD = "hld-test";

    private final OrderRepository orders = mock(OrderRepository.class);
    private final HoldFacade holds = mock(HoldFacade.class);
    private final CatalogFacade catalog = mock(CatalogFacade.class);
    private final OrderCommitService commit = mock(OrderCommitService.class);
    private final OrderRefundService refunds = mock(OrderRefundService.class);

    private final PaymentSettlementService settlement =
            new PaymentSettlementService(orders, holds, catalog, commit, refunds);

    private final PaymentSettledEvent event = new PaymentSettledEvent(HOLD, "pi_test", "pt_test", 5_000, "USD");

    @BeforeEach
    void aPendingOrder() {
        Order order = new Order("TK-TEST", HOLD, "session-test", "b@example.com", "tok", 1L, 5_000, "USD");
        when(orders.findByHoldToken(HOLD)).thenReturn(Optional.of(order));
    }

    @Test
    @DisplayName("A hold the checkout consumed after the first read goes to the refund claim, which declines it")
    void holdConsumedByTheCheckoutIsLeftToTheClaim() {
        // The checkout confirmed between onPaymentSettled's read and this claim: a CONSUMED hold
        // reads as expired. The claim fails because the order is CONFIRMED, and nothing moves.
        when(holds.getActiveHold(HOLD, "session-test")).thenThrow(new HoldExpiredException(HOLD, Instant.now()));
        when(refunds.refund("TK-TEST", "pt_test", 5_000, "webhook settled against a reservation that no longer exists"))
                .thenReturn(OrderRefundService.Outcome.RESOLVED_ELSEWHERE);

        assertThatCode(() -> settlement.onPaymentSettled(event)).doesNotThrowAnyException();

        verify(commit, never()).confirm(any(), any(), any(), any());
    }

    @Test
    @DisplayName("An ambiguous failure propagates so the provider redelivers, and moves no money")
    void ambiguousFailureIsNotRefunded() {
        when(holds.getActiveHold(HOLD, "session-test"))
                .thenReturn(new HoldSummary(HOLD, "session-test", 1L, 2L, 1, Instant.now(), Instant.now()));
        when(catalog.getTierSummary(1L, 2L)).thenThrow(new IllegalStateException("tier unreadable"));

        assertThatThrownBy(() -> settlement.onPaymentSettled(event)).isInstanceOf(IllegalStateException.class);

        verify(refunds, never()).refund(anyString(), any(), anyLong(), anyString());
    }

    @Test
    @DisplayName("An order already confirmed is left alone without touching the hold")
    void confirmedOrderIsInert() {
        Order confirmed = new Order("TK-TEST", HOLD, "session-test", "b@example.com", "tok", 1L, 5_000, "USD");
        confirmed.setStatus(OrderStatus.CONFIRMED);
        when(orders.findByHoldToken(HOLD)).thenReturn(Optional.of(confirmed));

        settlement.onPaymentSettled(event);

        verify(holds, never()).getActiveHold(any(), any());
        verify(refunds, never()).refund(anyString(), any(), anyLong(), anyString());
    }
}
