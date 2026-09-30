package com.flashseats.order.service;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flashseats.catalog.facade.CatalogFacade;
import com.flashseats.hold.exception.HoldExpiredException;
import com.flashseats.hold.facade.HoldFacade;
import com.flashseats.order.model.Order;
import com.flashseats.order.model.OrderStatus;
import com.flashseats.order.repository.OrderRepository;
import com.flashseats.payment.event.PaymentSettledEvent;
import com.flashseats.payment.facade.PaymentResult;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentSettlementServiceTest {

    @Mock
    private OrderRepository orders;

    @Mock
    private HoldFacade holds;

    @Mock
    private CatalogFacade catalog;

    @Mock
    private OrderCommitService commit;

    @Mock
    private OrderRefundService refunds;

    @Test
    void consumedHoldWithConfirmedOrderIsNotRefunded() {

        String holdToken = "hld-test";
        String orderNumber = "TK-TEST";
        Order order = order(orderNumber, holdToken, OrderStatus.PENDING);

        when(orders.findByHoldToken(holdToken))
                .thenReturn(Optional.of(order));

        when(holds.getActiveHold(holdToken, "session-test"))
                .thenThrow(new HoldExpiredException(holdToken, Instant.now()));

        /*
         * The webhook's first read sees the order as PENDING because that is the
         * object it started with. The re-read must observe the checkout that won
         * the race and changed the durable order to CONFIRMED.
         */
        Order confirmed = order(orderNumber, holdToken, OrderStatus.CONFIRMED);

        when(orders.findByHoldToken(holdToken))
                .thenReturn(Optional.of(order))
                .thenReturn(Optional.of(confirmed));

        PaymentSettlementService service =
                new PaymentSettlementService(
                        orders,
                        holds,
                        catalog,
                        commit,
                        refunds);

        service.onPaymentSettled(
                new PaymentSettledEvent(
                        holdToken,
                        "pi_test",
                        "pt_test",
                        5_000,
                        "USD"));

        verify(refunds, never())
                .refund(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyString());

        verify(commit, never())
                .confirm(
                        org.mockito.ArgumentMatchers.anyString(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any(PaymentResult.class));
    }

    private static Order order(
            String orderNumber,
            String holdToken,
            OrderStatus status) {

        Order order = new Order();
        order.setOrderNumber(orderNumber);
        order.setHoldToken(holdToken);
        order.setUserSessionId("session-test");
        order.setStatus(status);
        order.setTotalAmountCents(5_000);
        order.setCurrency("USD");

        return order;
    }
}