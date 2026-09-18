package com.loopers.interfaces.api.admin.product;

public class AdminProductV1Dto {
    public record ChangeStockRequest(int quantity) {}

    public record StockResponse(Long productId, int quantity) {}
}
