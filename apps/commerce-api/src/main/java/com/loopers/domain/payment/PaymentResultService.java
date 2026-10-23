package com.loopers.domain.payment;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로컬 TX B. create 응답·callback·reconcile 세 입구가 공유하며, 검증된 PG 결과를 시도·주문·예약에 한 번만 반영한다.
 * ⑥ 결제 시도 문지기(미종결일 때만 종결) → ⑦ 주문 상태(WHERE PAYMENT_PENDING) → ⑧ 예약(WHERE RESERVED, 실패면 재고 1회 복원)
 * 세 단계는 반드시 하나의 트랜잭션 안에 있어야 한다 (설계 5-2).
 */
@Component
public class PaymentResultService {

    /**
     * ⑥⑦⑧ 을 한 TX 로 실행한다.
     *
     * @return ⑥ 이 1행이면 true. 0행(이미 종결)이면 아무 효과 없이 false.
     */
    @Transactional
    public boolean applyFinal(Long attemptId, PaymentFinalResult result) {
        throw new UnsupportedOperationException("1단계 미구현");
    }

    /**
     * 생성 수락(PENDING) 기록. ⑥ 만 실행하고 ⑦⑧ 은 실행하지 않는다.
     *
     * @return 갱신된 행 수. 0 이면 이미 PENDING 이거나 종결된 시도다.
     */
    @Transactional
    public int recordPending(Long attemptId, String transactionKey) {
        throw new UnsupportedOperationException("1단계 미구현");
    }

    /**
     * 미확인(UNKNOWN) 기록. REQUESTED 일 때만 바꾸며 ⑦⑧ 은 실행하지 않는다.
     *
     * @return 갱신된 행 수
     */
    @Transactional
    public int recordUnknown(Long attemptId) {
        throw new UnsupportedOperationException("1단계 미구현");
    }
}