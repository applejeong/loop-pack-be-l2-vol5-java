package com.loopers.domain.payment;

/**
 * create 호출 1회의 관찰 결과.
 * ACCEPTED 는 생성 수락(PENDING + transactionKey), REJECTED_NO_EFFECT 는 거래 0건이 확정된 거절,
 * UNKNOWN 은 거래가 생겼는지 알 수 없는 경우(timeout·응답 유실·증거 없는 오류)다.
 */
public record PaymentCreateResult(Outcome outcome, String transactionKey, PaymentTransaction.Status status) {

    public enum Outcome {
        ACCEPTED, REJECTED_NO_EFFECT, UNKNOWN
    }

    public static PaymentCreateResult accepted(String transactionKey) {
        return new PaymentCreateResult(Outcome.ACCEPTED, transactionKey, PaymentTransaction.Status.PENDING);
    }

    public static PaymentCreateResult rejectedNoEffect() {
        return new PaymentCreateResult(Outcome.REJECTED_NO_EFFECT, null, null);
    }

    public static PaymentCreateResult unknown() {
        return new PaymentCreateResult(Outcome.UNKNOWN, null, null);
    }
}