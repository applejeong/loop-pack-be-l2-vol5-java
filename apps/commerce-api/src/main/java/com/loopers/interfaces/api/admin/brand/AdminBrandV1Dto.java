package com.loopers.interfaces.api.admin.brand;

import com.loopers.application.brand.BrandInfo;

public class AdminBrandV1Dto {
    public record CreateRequest(String name) {}

    public record BrandResponse(Long id, String name) {
        public static BrandResponse from(BrandInfo info) {
            return new BrandResponse(
                info.id(),
                info.name()
            );
        }
    }
}
