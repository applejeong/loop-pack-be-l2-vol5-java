package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.PurchaseActivityService;
import com.loopers.config.kafka.OrderActivityKafkaConfig;
import com.loopers.domain.purchase.OrderConfirmedEvent;
import com.loopers.domain.purchase.PermanentEventException;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * order-events 의 record 하나를 파싱 → 구매 활동 반영 → ack 한다 (설계 D12·D13).
 * - 영구 오류(PermanentEventException): 재시도 없이 ERROR 로그 후 ack. 구매 활동 없음
 * - 그 밖의 예외: backoff 재시도, 상한 초과 시 예외를 던져 CommonContainerStoppingErrorHandler 가 컨테이너를 중단.
 *   ack 하지 않으므로 offset 은 실패 record 앞에 머문다
 */
@Slf4j
@Component
public class OrderConfirmedListener {
    private final OrderConfirmedEventParser parser;
    private final PurchaseActivityService purchaseActivityService;
    private final int maxRetries;
    private final long initialBackoffMs;
    private final int multiplier;

    public OrderConfirmedListener(
        OrderConfirmedEventParser parser,
        PurchaseActivityService purchaseActivityService,
        @Value("${purchase-activity.retry.max-retries}") int maxRetries,
        @Value("${purchase-activity.retry.initial-backoff-ms}") long initialBackoffMs,
        @Value("${purchase-activity.retry.multiplier}") int multiplier
    ) {
        this.parser = parser;
        this.purchaseActivityService = purchaseActivityService;
        this.maxRetries = maxRetries;
        this.initialBackoffMs = initialBackoffMs;
        this.multiplier = multiplier;
    }

    @KafkaListener(
        topics = "${purchase-activity.topic}",
        groupId = "${purchase-activity.group-id}",
        containerFactory = OrderActivityKafkaConfig.ORDER_ACTIVITY_LISTENER
    )
    public void listen(ConsumerRecord<String, byte[]> record, Acknowledgment ack) {
        try {
            OrderConfirmedEvent event = parser.parse(record.key(), record.value());
            consumeWithRetry(event);
        } catch (PermanentEventException e) {
            log.error("영구 오류라 재시도 없이 건너뜁니다 (구매 활동 없음). topic={}, partition={}, offset={}, key={}: {}",
                record.topic(), record.partition(), record.offset(), record.key(), e.getMessage());
        }
        ack.acknowledge();
    }

    /**
     * 리스너에는 TX 가 없으므로 service 호출마다 새 TX 가 열리고, 실패한 시도는 그 TX 와 함께 rollback 된다.
     */
    private void consumeWithRetry(OrderConfirmedEvent event) {
        long backoffMs = initialBackoffMs;
        for (int attempt = 1; ; attempt++) {
            try {
                purchaseActivityService.consume(event);
                return;
            } catch (PermanentEventException e) {
                throw e;
            } catch (RuntimeException e) {
                if (attempt > maxRetries) {
                    log.error("일시 오류 재시도 소진 ({}회 시도). 컨테이너를 중단합니다. eventId={}, orderId={}",
                        attempt, event.eventId(), event.orderId(), e);
                    throw e;
                }
                log.warn("일시 오류, {}ms 후 재시도 ({}/{}). eventId={}, orderId={}: {}",
                    backoffMs, attempt, maxRetries, event.eventId(), event.orderId(), e.toString());
                sleep(backoffMs);
                backoffMs *= multiplier;
            }
        }
    }

    private static void sleep(long backoffMs) {
        try {
            Thread.sleep(backoffMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("재시도 대기 중 인터럽트", e);
        }
    }
}
