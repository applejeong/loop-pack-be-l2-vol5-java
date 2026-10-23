package com.loopers.infrastructure.payment;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * 가짜 PG 를 필요한 테스트에만 올린다. 컴포넌트 스캔에 잡히지 않으므로 @Import 로만 등록된다.
 * 실제 HTTP adapter 가 생기면(3단계) 이 빈에 @Primary 를 붙여 테스트에서 포트 구현을 하나로 고른다.
 */
@TestConfiguration
public class FakePaymentGatewayConfig {

    @Bean
    public FakePaymentGateway fakePaymentGateway() {
        return new FakePaymentGateway();
    }
}