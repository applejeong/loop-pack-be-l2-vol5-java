package com.loopers.infrastructure.payment;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * 실제 HTTP adapter 설정 (설계 1-6 결정 ㉒·㉓·㉔). 값은 application.yml 의 payment.pg 에서 바인딩된다.
 * base URL·callback URL·제공자 계정(X-USER-ID)은 설정에 고정하고 고객 입력으로 바꾸지 않는다.
 * enabled 가 false 면 adapter 는 어떤 호출이든 즉시 예외를 던진다 (test 프로필 기본값). 가짜 PG 를 올리지 않은 테스트가 실제 HTTP 를 보내 조용히 UNKNOWN 으로 끝나는 것을 막는다.
 * 두 timeout 은 3단계에서는 자리와 임시값만 두고 4단계에서 확정한다. 0(무한 대기)은 두지 않는다.
 */
@ConfigurationProperties(value = "payment.pg")
public record PaymentGatewayProperties(
    boolean enabled,
    String baseUrl,
    String callbackUrl,
    String userId,
    Duration connectTimeout,
    Duration readTimeout
) { }