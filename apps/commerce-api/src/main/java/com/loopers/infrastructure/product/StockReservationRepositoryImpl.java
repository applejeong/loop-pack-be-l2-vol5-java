package com.loopers.infrastructure.product;

import com.loopers.domain.product.StockReservation;
import com.loopers.domain.product.StockReservationRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

@RequiredArgsConstructor
@Component
public class StockReservationRepositoryImpl implements StockReservationRepository {
    private final StockReservationJpaRepository stockReservationJpaRepository;

    @Override
    public StockReservation save(StockReservation reservation) {
        return stockReservationJpaRepository.save(reservation);
    }

    @Override
    public List<StockReservation> findAllByOrderId(Long orderId) {
        return stockReservationJpaRepository.findAllByOrderId(orderId);
    }

    @Override
    public int consumeIfReserved(Long reservationId) {
        throw new UnsupportedOperationException("1단계 미구현");
    }

    @Override
    public int releaseIfReserved(Long reservationId) {
        throw new UnsupportedOperationException("1단계 미구현");
    }
}