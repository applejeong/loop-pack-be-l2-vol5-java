package com.loopers.domain.payment;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 로컬 TX A. 하나의 트랜잭션 안에서 아래 순서로 진행하고, 어느 단계든 거절되면 PG 호출 없이 전체 rollback 한다.
 * ① 주문 행 잠금(SELECT … FOR UPDATE) 후 소유권 → 기존 시도(키·요청 일치) → DRAFT 순으로 판정
 * ② 주문 DRAFT → PAYMENT_PENDING, 결제수단 PG
 * ③ 품목을 productId 오름차순으로 재고 차감(WHERE stock >= n) + 예약 RESERVED 저장
 * ④ 결제 시도 REQUESTED 저장 (UNIQUE order_id, UNIQUE user_id+attempt_key)
 * commit 이 발송 시작 기록이다 (설계 1-2, 3장).
 */
@Component
public class PaymentStartService {

    @Transactional
    public PaymentStart start(Long userId, Long orderId, PaymentStartCommand command) {
        throw new UnsupportedOperationException("1단계 미구현");
    }
}