package com.flashseats.payment.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.flashseats.payment.gateway.GatewayResult;
import com.flashseats.payment.model.PaymentStatus;
import com.flashseats.payment.model.PaymentTransaction;
import com.flashseats.payment.repository.PaymentTransactionRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.flashseats.payment.facade.AuthorizeCommand;

class PaymentTransactionStoreTest {

    private final PaymentTransactionRepository transactions = mock(PaymentTransactionRepository.class);

    private final PaymentTransactionStore store = new PaymentTransactionStore(transactions);

    @Test
    void requiresActionIsStoredAsProcessingWithGatewayReference() {
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setTransactionReference("tx_1");
        transaction.setStatus(PaymentStatus.INITIATED);

        when(transactions.findByTransactionReference("tx_1")).thenReturn(Optional.of(transaction));

        GatewayResult result = GatewayResult.requiresAction("pi_123", "pi_123_secret");

        store.recordOutcome("tx_1", result);

        assertThat(transaction.getStatus()).isEqualTo(PaymentStatus.PROCESSING);
        assertThat(transaction.getGatewayReference()).isEqualTo("pi_123");
        assertThat(transaction.getFailureCode()).isNull();
        assertThat(transaction.getFailureReason()).isNull();
    }
    @Test
    void processingTransactionIsResumedInsteadOfCreatingANewAttempt() {
        PaymentTransaction pending = new PaymentTransaction();
        pending.setTransactionReference("tx_existing");
        pending.setGatewayReference("pi_existing");
        pending.setStatus(PaymentStatus.PROCESSING);

        when(transactions.findFirstByHoldTokenAndStatusOrderByIdDesc(
                "hold-42",
                PaymentStatus.PROCESSING))
                .thenReturn(Optional.of(pending));

        var command = new AuthorizeCommand(
                "order-42",
                "hold-42",
                "session-7",
                7_500,
                "usd",
                "pm_card_visa",
                "idem-42",
                1);

        ChargeAttempt attempt = store.beginAttempt(command);

        assertThat(attempt.transactionReference())
                .isEqualTo("tx_existing");
        assertThat(attempt.resumableGatewayReference())
                .isEqualTo("pi_existing");
        assertThat(attempt.isResume())
                .isTrue();

        verify(transactions, never())
                .save(any(PaymentTransaction.class));
    }
    @Test
    void declineDoesNotEraseAnExistingGatewayReference() {
        PaymentTransaction transaction = new PaymentTransaction();
        transaction.setTransactionReference("tx_1");
        transaction.setGatewayReference("pi_existing");
        transaction.setStatus(PaymentStatus.PROCESSING);

        when(transactions.findByTransactionReference("tx_1"))
                .thenReturn(Optional.of(transaction));

        GatewayResult result =
                GatewayResult.declined(
                        "card_declined",
                        "Your card was declined.");

        store.recordOutcome("tx_1", result);

        assertThat(transaction.getStatus())
                .isEqualTo(PaymentStatus.FAILED);
        assertThat(transaction.getGatewayReference())
                .isEqualTo("pi_existing");
        assertThat(transaction.getFailureCode())
                .isEqualTo("card_declined");
        assertThat(transaction.getFailureReason())
                .isEqualTo("Your card was declined.");
    }
    
}