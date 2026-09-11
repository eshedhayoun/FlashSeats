package com.flashseats.payment.gateway;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Chooses the payment provider.
 *
 * <p>{@link ConditionalOnMissingBean} is the seam: defining any other {@link PaymentGateway} bean —
 * a Stripe one, or a test double that fails after charging — replaces the stub, and nothing above it
 * changes.
 */
@Configuration
public class PaymentGatewayConfig {

    @Bean
    @ConditionalOnMissingBean(PaymentGateway.class)
    public PaymentGateway stubPaymentGateway() {
        return new StubPaymentGateway();
    }
}
