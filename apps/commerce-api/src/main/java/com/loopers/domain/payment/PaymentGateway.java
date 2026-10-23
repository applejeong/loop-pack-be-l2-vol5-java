package com.loopers.domain.payment;

import java.util.List;
import java.util.Optional;

/**
 * 로컬 테스트 PG 포트. HTTP 응답이 아니라 "관찰 결과"를 돌려준다.
 * HTTP 요청·응답 매핑은 infrastructure 구현(3단계)이 맡는다.
 */
public interface PaymentGateway {

    /**
     * 거래 생성을 1회 요청한다. 자동 재시도는 하지 않는다.
     */
    PaymentCreateResult create(PaymentCreateCommand command);

    /**
     * 같은 주문(providerOrderId)의 거래 목록. 빈 목록도 표현한다.
     */
    List<PaymentTransaction> findAllByOrderId(String providerOrderId);

    /**
     * 거래 단건 조회. 없으면 empty.
     */
    Optional<PaymentTransaction> findByTransactionKey(String transactionKey);
}