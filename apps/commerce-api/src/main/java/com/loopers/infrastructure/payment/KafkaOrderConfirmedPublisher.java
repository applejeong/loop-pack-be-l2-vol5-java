package com.loopers.infrastructure.payment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.payment.OrderConfirmedPublisher;
import com.loopers.config.kafka.OrderEventKafkaConfig;
import com.loopers.domain.order.OrderConfirmedEvent;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * order-events 로 OrderConfirmed 를 보낸다 (6주차 설계 D6·D7).
 * - key = orderId 문자열, value = ObjectMapper 로 만든 JSON byte[]
 * - broker 저장 확인까지 동기로 기다리고(상한 send-timeout-ms) 성공한 record 의 topic·partition·offset 을 로그로 남긴다
 * - 실패는 eventId·orderId 를 담은 IllegalStateException 으로 올린다. 주문 상태를 되돌리거나 PG 를 재호출하지 않는 것은 호출자의 책임이다
 */
@Slf4j
@Component
public class KafkaOrderConfirmedPublisher implements OrderConfirmedPublisher {

    private final ObjectMapper objectMapper;
    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final String topic;
    private final long sendTimeoutMs;

    public KafkaOrderConfirmedPublisher(
        ObjectMapper objectMapper,
        @Qualifier(OrderEventKafkaConfig.ORDER_EVENT_KAFKA_TEMPLATE) KafkaTemplate<String, byte[]> kafkaTemplate,
        @Value("${order-events.topic}") String topic,
        @Value("${order-events.send-timeout-ms}") long sendTimeoutMs
    ) {
        this.objectMapper = objectMapper;
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.sendTimeoutMs = sendTimeoutMs;
    }

    @Override
    public void publish(OrderConfirmedEvent event) {
        byte[] value;
        try {
            value = objectMapper.writeValueAsBytes(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("OrderConfirmed 직렬화 실패. eventId=" + event.eventId() + ", orderId=" + event.orderId(), e);
        }

        try {
            RecordMetadata metadata = kafkaTemplate.send(topic, event.key(), value)
                .get(sendTimeoutMs, TimeUnit.MILLISECONDS)
                .getRecordMetadata();
            log.info("OrderConfirmed 발행 완료. eventId={}, orderId={}, topic={}, partition={}, offset={}",
                event.eventId(), event.orderId(), metadata.topic(), metadata.partition(), metadata.offset());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("OrderConfirmed 발행 대기 중 인터럽트. eventId=" + event.eventId(), e);
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            throw new IllegalStateException(
                "OrderConfirmed 발행 실패. eventId=" + event.eventId() + ", orderId=" + event.orderId() + ", topic=" + topic, e);
        }
    }
}
