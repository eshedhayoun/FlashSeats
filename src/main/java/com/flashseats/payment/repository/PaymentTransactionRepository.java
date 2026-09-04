package com.flashseats.payment.repository;

import com.flashseats.payment.model.PaymentTransaction;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentTransactionRepository extends JpaRepository<PaymentTransaction, Long> {

    Optional<PaymentTransaction> findByTransactionReference(String transactionReference);
}
