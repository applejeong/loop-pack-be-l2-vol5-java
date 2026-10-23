package com.loopers.domain.payment;

import java.util.Optional;

public interface PaymentAttemptRepository {
    PaymentAttempt save(PaymentAttempt attempt);

    Optional<PaymentAttempt> findById(Long id);

    Optional<PaymentAttempt> findByOrderId(Long orderId);

    Optional<PaymentAttempt> findByUserIdAndAttemptKey(Long userId, String attemptKey);

    /**
     * TX B ⑥ 결과 반영 문지기. 미종결(REQUESTED·PENDING·UNKNOWN)일 때만 SUCCESS·FAILED 로 종결한다.
     * WHERE status IN ('REQUESTED', 'PENDING', 'UNKNOWN').
     *
     * @return 갱신된 행 수. 0 이면 이미 종결된 시도라 아무 효과도 없어야 한다.
     */
    int settleIfOpen(Long attemptId, PaymentAttempt.Status status, PaymentAttempt.FailureReason failureReason, String transactionKey);

    /**
     * 생성 수락 또는 조회로 거래를 확인했을 때 PENDING 과 transactionKey 를 기록한다.
     * WHERE status IN ('REQUESTED', 'UNKNOWN'). 종결된 시도는 되돌리지 않는다.
     *
     * @return 갱신된 행 수
     */
    int markPendingIfNotSettled(Long attemptId, String transactionKey);

    /**
     * timeout·응답 유실을 UNKNOWN 으로 기록한다. WHERE status = 'REQUESTED'. 이미 PENDING 이면 되돌리지 않는다.
     *
     * @return 갱신된 행 수
     */
    int markUnknownIfRequested(Long attemptId);
}