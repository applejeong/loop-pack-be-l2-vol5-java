package com.loopers.interfaces.api.admin.brand;

import com.loopers.interfaces.api.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.tags.Tag;

@Tag(name = "Admin Brand V1 API", description = "Loopers 관리자 브랜드 API 입니다.")
public interface AdminBrandV1ApiSpec {

    @Operation(
        summary = "브랜드 등록",
        description = "새로운 브랜드를 등록합니다."
    )
    ApiResponse<AdminBrandV1Dto.BrandResponse> createBrand(
        @Schema(name = "브랜드 등록 요청", description = "등록할 브랜드의 이름")
        AdminBrandV1Dto.CreateRequest request
    );

    @Operation(
        summary = "브랜드 상세 조회",
        description = "ID로 브랜드를 조회합니다."
    )
    ApiResponse<AdminBrandV1Dto.BrandResponse> getBrand(
        @Schema(name = "브랜드 ID", description = "조회할 브랜드의 ID")
        Long brandId
    );
}
