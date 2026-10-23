package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.payment.PaymentCreateCommand;
import com.loopers.domain.payment.PaymentCreateResult;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentLookupResult;

/**
 * 로컬 테스트 PG 의 실제 HTTP adapter (설계 1-6 결정 ㉑~㉖, 7-3).
 * RestClient + JDK HttpClient(JdkClientHttpRequestFactory) 를 이 클래스 안에서 조립한다. 프록시를 쓰지 않고, 연결·응답 timeout 은 설정값이다.
 * HTTP 요청·응답 본문은 로그에 남기지 않는다 (요청 본문에 카드 번호가 있다).
 * 응답 해석: 202 + PENDING → ACCEPTED, 422 + 정확한 code + boolean false + orderId·amount 일치 → REJECTED_NO_EFFECT,
 * 202·422 인데 orderId·amount 불일치 → UNKNOWN + 충돌 사유(key 저장 안 함), 그 밖의 응답·timeout·연결 실패 → UNKNOWN.
 * 조회는 목록·단건 모두 200 → FOUND + 거래, 404(단건) → FOUND + 0건, 5xx·잘못된 JSON·timeout → UNAVAILABLE.
 * 운영 빈 등록(@Component)과 UnavailablePaymentGateway 삭제는 3단계-B 에서 한다.
 */
public class HttpPaymentGateway implements PaymentGateway {

    private final PaymentGatewayProperties properties;
    private final ObjectMapper objectMapper;

    public HttpPaymentGateway(PaymentGatewayProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /**
     * POST /api/v1/payments. 본문은 orderId·cardType·cardNo·amount(정수)·callbackUrl(설정값). 헤더 X-USER-ID 는 설정값.
     */
    @Override
    public PaymentCreateResult create(PaymentCreateCommand command) {
        throw new UnsupportedOperationException("3단계 미구현");
    }

    /**
     * GET /api/v1/payments?orderId={providerOrderId}. 쿼리는 URI builder 로 인코딩한다.
     */
    @Override
    public PaymentLookupResult findAllByOrderId(String providerOrderId) {
        throw new UnsupportedOperationException("3단계 미구현");
    }

    /**
     * GET /api/v1/payments/{transactionKey}. 200 → FOUND + 1건, 404 → FOUND + 0건, 그 밖의 실패 → UNAVAILABLE.
     */
    @Override
    public PaymentLookupResult findByTransactionKey(String transactionKey) {
        throw new UnsupportedOperationException("3단계 미구현");
    }
}