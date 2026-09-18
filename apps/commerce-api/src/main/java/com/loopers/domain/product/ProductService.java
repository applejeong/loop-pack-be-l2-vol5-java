package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@RequiredArgsConstructor
@Component
public class ProductService {

    private final ProductRepository productRepository;

    /**
     * 증감이 아니라 최종 수량으로 재고를 설정한다.
     */
    @Transactional
    public Product changeStock(Long productId, int quantity) {
        Product product = findActiveProduct(productId);
        product.changeStock(quantity);
        return product;
    }

    @Transactional
    public Product deductStock(Long productId, int quantity) {
        Product product = findActiveProduct(productId);
        product.deductStock(quantity);
        return product;
    }

    /**
     * 존재하며 삭제되지 않은 상품을 반환한다.
     */
    private Product findActiveProduct(Long productId) {
        Product product = productRepository.findById(productId)
            .orElseThrow(() -> notFound(productId));

        if (product.getDeletedAt() != null) {
            throw notFound(productId);
        }
        return product;
    }

    private CoreException notFound(Long productId) {
        return new CoreException(ErrorType.NOT_FOUND, "[id = " + productId + "] 상품을 찾을 수 없습니다.");
    }
}
