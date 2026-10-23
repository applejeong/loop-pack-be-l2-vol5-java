package com.loopers.application.payment;

import com.loopers.application.order.OrderConfirmInfo;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentResultService;
import com.loopers.domain.user.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;

/**
 * callback·reconcile·스케줄러의 조율 (설계 6장). 세 입구 모두 "PG 권위 조회 → 네 항목 대조 → TX B(PaymentResultService) 전달"을 공유한다.
 * OrderFacade 와 서로 호출하지 않는다. 이 클래스의 메서드는 트랜잭션을 갖지 않는다. PG 조회 동안 DB 트랜잭션을 보유하지 않기 위해서다.
 */
@RequiredArgsConstructor
@Component
public class PaymentFacade {

    private final UserRepository userRepository;
    private final OrderService orderService;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentResultService paymentResultService;
    private final PaymentGateway paymentGateway;
    private final PaymentLookupProperties lookupProperties;
    private final Clock clock;

    /**
     * POST /api/v1/payments/callback (설계 7-5 결정 ⑲·⑬).
     * 시도 없음 → NOT_FOUND. 종결 → 조회 없이 저장 결과. 그 외 목록 조회 후 반영.
     * 본문의 status 는 신뢰하지 않으므로 받지 않는다. transactionKey 는 목록의 거래와 대조하는 데만 쓴다.
     * 조회 실패 → INTERNAL_ERROR(5xx), 충돌 → CONFLICT(409) + 충돌 기록.
     */
    public OrderConfirmInfo applyCallback(String providerOrderId, String transactionKey) {
        throw new UnsupportedOperationException("2단계 미구현");
    }

    /**
     * POST /api/v1/orders/{orderId}/payment/reconcile (설계 5-6 결정 ⑳).
     * 사용자 식별 → 소유권 → 시도 없음(NOT_FOUND) → 종결이면 조회 없이 저장 결과 → 목록 조회 후 반영.
     * 조회 실패·충돌은 예외가 아니라 lookupUnavailable=true 로 돌려준다. 조회 횟수(lookupCount)는 세지 않는다.
     */
    public OrderConfirmInfo reconcile(Long requesterId, Long orderId) {
        throw new UnsupportedOperationException("2단계 미구현");
    }

    /**
     * 스케줄러 1회 실행 (설계 5-3 결정 ⑫). 미종결 + 최소 경과 시간이 지난 시도만 골라 건별로 조회·반영·조회 기록을 하고,
     * 한 건의 예외는 잡아 다음 건으로 넘어간다. @Transactional 을 걸지 않는다.
     */
    public void runScheduledLookups() {
        throw new UnsupportedOperationException("2단계 미구현");
    }
}
