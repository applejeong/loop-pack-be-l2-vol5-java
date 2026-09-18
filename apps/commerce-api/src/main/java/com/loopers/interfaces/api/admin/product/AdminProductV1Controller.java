package com.loopers.interfaces.api.admin.product;

import com.loopers.application.product.ProductFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/products")
public class AdminProductV1Controller {

    private final ProductFacade productFacade;

    @PutMapping("/{productId}/stock")
    public ApiResponse<AdminProductV1Dto.StockResponse> changeStock(
        @PathVariable(value = "productId") Long productId,
        @RequestBody AdminProductV1Dto.ChangeStockRequest request
    ) {
        int quantity = productFacade.changeStock(productId, request.quantity());
        return ApiResponse.success(new AdminProductV1Dto.StockResponse(productId, quantity));
    }
}
