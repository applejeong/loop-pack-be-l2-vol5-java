package com.loopers.domain.payment;

import java.util.List;

/**
 * 같은 주문(providerOrderId)의 거래 목록 조회 1회의 관찰 결과.
 * FOUND 는 조회가 되었다는 뜻이고 목록은 0건·1건·2건 이상 모두 가능하다.
 * UNAVAILABLE 은 timeout·응답 유실·회로 차단(4단계)처럼 PG 에 물어보지 못한 경우다. 0건과 다르다 (설계 7-4·7-6).
 */
public record PaymentLookupResult(Outcome outcome, List<PaymentTransaction> transactions) {

    public enum Outcome {
        FOUND, UNAVAILABLE
    }

    public static PaymentLookupResult found(List<PaymentTransaction> transactions) {
        return new PaymentLookupResult(Outcome.FOUND, List.copyOf(transactions));
    }

    public static PaymentLookupResult unavailable() {
        return new PaymentLookupResult(Outcome.UNAVAILABLE, List.of());
    }

    public boolean isUnavailable() {
        return outcome == Outcome.UNAVAILABLE;
    }
}
