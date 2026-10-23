package com.loopers.domain.order;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {
    Order save(Order order);

    Optional<Order> findById(Long id);

    List<Order> findAllByUserId(Long userId);

    /**
     * 관리자 조회용. userId 가 null 이면 전체 주문을 최신순으로 반환한다.
     */
    List<Order> findAllForAdmin(Long userId, int page, int size);

    /**
     * DRAFT 인 본인 주문만 CONFIRMED 로 바꾸고 결제액을 기록한다.
     * 메모리에서 하던 "DRAFT 아님 → 거절" 상태 검증과 상태·결제액 변경을 WHERE 조건부 UPDATE 로 대신한다.
     *
     * @return 갱신된 행 수. 0 이면 이미 확정됐거나 본인 주문이 아니다.
     */
    int confirmIfDraft(Long orderId, Long userId, long paidAmount);

    /**
     * 조건부 UPDATE 뒤 영속성 컨텍스트의 주문을 DB 상태로 다시 읽는다. 컨텍스트 전체를 비우지 않는다.
     */
    Order reload(Order order);

    /**
     * TX A ①. 주문 행을 비관적 락(SELECT … FOR UPDATE)으로 읽는다.
     * 소유권 → 기존 시도 → DRAFT 순 판정을 한 WHERE 로 정할 수 없어 잠근 뒤 최신 커밋 값을 읽는다.
     */
    Optional<Order> findByIdForUpdate(Long orderId);

    /**
     * TX B ⑦. WHERE status = 'PAYMENT_PENDING' 일 때만 CONFIRMED(+paidAmount) 또는 PAYMENT_FAILED 로 바꾼다.
     *
     * @return 갱신된 행 수. 0 이면 이미 종결된 주문이다.
     */
    int finishIfPaymentPending(Long orderId, Order.OrderStatus status, long paidAmount);
}
