package com.loopers.domain.purchase;

import com.loopers.domain.BaseEntity;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;

import java.time.ZonedDateTime;

/**
 * 소비 완료한 OrderConfirmed 이벤트의 장부 (설계 D10). 이벤트당 1행이며 eventId·orderId 가 각각 유일하다.
 * fingerprint 는 정규화한 payload 문자열로, 같은 eventId 로 다른 사실이 오면 이 값으로 충돌을 드러낸다.
 */
@Getter
@Entity
@Table(
    name = "processed_order_confirmation",
    uniqueConstraints = {
        @UniqueConstraint(name = "uk_poc_event_id", columnNames = "event_id"),
        @UniqueConstraint(name = "uk_poc_order_id", columnNames = "order_id")
    }
)
public class ProcessedOrderConfirmation extends BaseEntity {

    private String eventId;

    private Long orderId;

    private ZonedDateTime confirmedAt;

    private String fingerprint;

    private ZonedDateTime processedAt;

    protected ProcessedOrderConfirmation() {}

    public ProcessedOrderConfirmation(String eventId, Long orderId, ZonedDateTime confirmedAt, String fingerprint) {
        this.eventId = eventId;
        this.orderId = orderId;
        this.confirmedAt = confirmedAt;
        this.fingerprint = fingerprint;
        this.processedAt = ZonedDateTime.now();
    }
}
