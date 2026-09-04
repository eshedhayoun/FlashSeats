package com.flashseats.flashseats.payment.facade;
import com.flashseats.flashseats.payment.dto.PaymentRequest;
import com.flashseats.flashseats.payment.dto.PaymentResult;

/*interface for payment facade written genriclly on purpose so it may be used later for further implementation */
public interface PaymentFacade {
    PaymentResult processPayment(PaymentRequest pRequest);
}
