package com.loopers.domain.purchase;

/**
 * 다시 처리해도 결과가 같은 영구 오류 (설계 D12): 깨진 JSON, 계약 위반, key ≠ orderId, 수량 ≤ 0, 같은 ID 의 다른 사실(충돌).
 * 리스너는 이 예외를 재시도하지 않고 ERROR 로그 후 ack 한다. 구매 활동은 만들지 않는다.
 */
public class PermanentEventException extends RuntimeException {

    public PermanentEventException(String message) {
        super(message);
    }

    public PermanentEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
