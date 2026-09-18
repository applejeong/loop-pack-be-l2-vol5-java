package com.loopers.application.brand;

import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class BrandFacade {

    private final BrandRepository brandRepository;

    @Transactional
    public BrandInfo createBrand(String name) {
        Brand brand = brandRepository.save(new Brand(name));
        return BrandInfo.from(brand);
    }

    @Transactional(readOnly = true)
    public BrandInfo getBrand(Long id) {
        return BrandInfo.from(findActiveBrand(id));
    }

    /**
     * 존재하며 삭제되지 않은 브랜드만 반환한다.
     * 브랜드 CRUD 가 늘어나면 이 책임을 BrandService 로 옮긴다.
     */
    private Brand findActiveBrand(Long id) {
        Brand brand = brandRepository.findById(id)
            .orElseThrow(() -> notFound(id));

        if (brand.getDeletedAt() != null) {
            throw notFound(id);
        }
        return brand;
    }

    private CoreException notFound(Long id) {
        return new CoreException(ErrorType.NOT_FOUND, "[id = " + id + "] 브랜드를 찾을 수 없습니다.");
    }
}
