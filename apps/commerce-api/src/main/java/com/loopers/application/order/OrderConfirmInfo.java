package com.loopers.application.order;

/**
 * confirm 응답 재료. settled 가 false 면 202(진행 중), true 면 200(종결)으로 응답한다.
 * transactionKey 와 카드 번호는 담지 않는다 (설계 4-6).
 */
public record OrderConfirmInfo(OrderInfo order, String attemptStatus, boolean settled) {}