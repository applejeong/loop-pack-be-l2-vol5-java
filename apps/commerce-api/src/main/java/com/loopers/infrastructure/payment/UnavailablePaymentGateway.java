package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentCreateCommand;
import com.loopers.domain.payment.PaymentCreateResult;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentLookupResult;
import com.loopers.domain.payment.PaymentTransaction;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 실제 HTTP adapter(3단계)가 생기기 전까지의 운영 껍데기. 호출되면 즉시 예외를 던진다.
 * 가짜 PG 를 올리지 않은 테스트가 실수로 PG 를 부르면 조용히 넘어가지 않고 크게 실패해 드러나게 한다.
 */
@Component
public class UnavailablePaymentGateway implements PaymentGateway {

    @Override
    public PaymentCreateResult create(PaymentCreateCommand command) {
        throw unavailable();
    }

    @Override
    public PaymentLookupResult findAllByOrderId(String providerOrderId) {
        throw unavailable();
    }

    @Override
    public Optional<PaymentTransaction> findByTransactionKey(String transactionKey) {
        throw unavailable();
    }

    private static IllegalStateException unavailable() {
        return new IllegalStateException("PG adapter 가 설정되지 않았다. 테스트라면 FakePaymentGatewayConfig 를 @Import 했는지 확인한다.");
    }
}