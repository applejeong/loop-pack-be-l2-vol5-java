package com.loopers.interfaces.api.admin.brand;

import com.loopers.application.brand.BrandFacade;
import com.loopers.domain.brand.Brand;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.brand.BrandV1Dto;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/brands")
public class AdminBrandV1Controller {

    private final BrandFacade brandFacade;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<BrandV1Dto.BrandResponse> createBrand(
        @RequestBody AdminBrandV1Dto.CreateRequest request
    ) {
        Brand brand = brandFacade.createBrand(request.name());
        return ApiResponse.success(BrandV1Dto.BrandResponse.from(brand));
    }

    @GetMapping("/{brandId}")
    public ApiResponse<BrandV1Dto.BrandResponse> getBrand(
        @PathVariable(value = "brandId") Long brandId
    ) {
        Brand brand = brandFacade.getBrand(brandId);
        return ApiResponse.success(BrandV1Dto.BrandResponse.from(brand));
    }
}
