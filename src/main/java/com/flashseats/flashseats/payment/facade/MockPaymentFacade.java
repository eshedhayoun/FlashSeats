package com.flashseats.flashseats.payment.facade;
import com.flashseats.flashseats.payment.dto.PaymentRequest;
import com.flashseats.flashseats.payment.dto.PaymentResult;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class MockPaymentFacade implements PaymentFacade {
    @Override
    public PaymentResult processPayment(PaymentRequest pRequest){
        return new PaymentResult(
            true,
            "MOCK UUID" + UUID.randomUUID()
        );
    }
}
