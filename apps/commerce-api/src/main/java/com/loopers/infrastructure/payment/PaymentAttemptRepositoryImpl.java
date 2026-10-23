package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;

@RequiredArgsConstructor
@Component
public class PaymentAttemptRepositoryImpl implements PaymentAttemptRepository {
    private final PaymentAttemptJpaRepository paymentAttemptJpaRepository;

    @Override
    public PaymentAttempt save(PaymentAttempt attempt) {
        return paymentAttemptJpaRepository.save(attempt);
    }

    @Override
    public Optional<PaymentAttempt> findById(Long id) {
        return paymentAttemptJpaRepository.findById(id);
    }

    @Override
    public Optional<PaymentAttempt> findByOrderId(Long orderId) {
        return paymentAttemptJpaRepository.findByOrderId(orderId);
    }

    @Override
    public Optional<PaymentAttempt> findByUserIdAndAttemptKey(Long userId, String attemptKey) {
        return paymentAttemptJpaRepository.findByUserIdAndAttemptKey(userId, attemptKey);
    }

    @Override
    public int settleIfOpen(
        Long attemptId,
        PaymentAttempt.Status status,
        PaymentAttempt.FailureReason failureReason,
        String transactionKey
    ) {
        throw new UnsupportedOperationException("1단계 미구현");
    }

    @Override
    public int markPendingIfNotSettled(Long attemptId, String transactionKey) {
        throw new UnsupportedOperationException("1단계 미구현");
    }

    @Override
    public int markUnknownIfRequested(Long attemptId) {
        throw new UnsupportedOperationException("1단계 미구현");
    }
}