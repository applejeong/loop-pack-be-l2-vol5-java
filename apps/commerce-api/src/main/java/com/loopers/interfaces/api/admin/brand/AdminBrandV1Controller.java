package com.loopers.interfaces.api.admin.brand;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.brand.BrandInfo;
import com.loopers.interfaces.api.ApiResponse;
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
public class AdminBrandV1Controller implements AdminBrandV1ApiSpec {

    private final BrandFacade brandFacade;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Override
    public ApiResponse<AdminBrandV1Dto.BrandResponse> createBrand(
        @RequestBody AdminBrandV1Dto.CreateRequest request
    ) {
        BrandInfo info = brandFacade.createBrand(request.name());
        AdminBrandV1Dto.BrandResponse response = AdminBrandV1Dto.BrandResponse.from(info);
        return ApiResponse.success(response);
    }

    @GetMapping("/{brandId}")
    @Override
    public ApiResponse<AdminBrandV1Dto.BrandResponse> getBrand(
        @PathVariable(value = "brandId") Long brandId
    ) {
        BrandInfo info = brandFacade.getBrand(brandId);
        AdminBrandV1Dto.BrandResponse response = AdminBrandV1Dto.BrandResponse.from(info);
        return ApiResponse.success(response);
    }
}
