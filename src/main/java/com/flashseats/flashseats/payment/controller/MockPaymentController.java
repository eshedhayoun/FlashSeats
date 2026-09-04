package com.flashseats.flashseats.payment.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.flashseats.flashseats.payment.dto.PaymentRequest;
import com.flashseats.flashseats.payment.dto.PaymentResult;
import com.flashseats.flashseats.payment.facade.PaymentFacade;

@RestController
@RequestMapping("/api/v1/payments")
public class MockPaymentController {

    private final PaymentFacade pFacade;
    public MockPaymentController(PaymentFacade pFacade){
        this.pFacade = pFacade;
    }
    @PostMapping("/mock")
    public ResponseEntity<PaymentResult> processMockPayment(@RequestBody PaymentRequest request) {
        
        PaymentResult result = pFacade.processPayment(request);
        return ResponseEntity.ok(result);
    }
}
