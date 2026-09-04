package com.flashseats.payment.facade;

import com.flashseats.payment.service.PaymentService;
import org.springframework.stereotype.Component;

/** Thin delegation to {@link PaymentService}. Package-private. */
@Component
class PaymentFacadeImpl implements PaymentFacade {

    private final PaymentService payments;

    PaymentFacadeImpl(PaymentService payments) {
        this.payments = payments;
    }

    @Override
    public PaymentResult authorize(AuthorizeCommand command) {
        return payments.authorize(command);
    }

    @Override
    public RefundResult refund(String transactionReference, long amountCents, String reason) {
        return payments.refund(transactionReference, amountCents, reason);
    }
}
