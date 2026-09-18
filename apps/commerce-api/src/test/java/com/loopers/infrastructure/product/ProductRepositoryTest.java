package com.loopers.infrastructure.product;

import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ProductRepositoryTest {

    private final ProductRepository productRepository;
    private final EntityManager entityManager;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public ProductRepositoryTest(
        ProductRepository productRepository,
        EntityManager entityManager,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.productRepository = productRepository;
        this.entityManager = entityManager;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("상품을 저장할 때, ")
    @Nested
    class Save {
        @DisplayName("저장 후 flush·clear 하고 다시 조회하면, 저장한 값이 그대로 조회된다.")
        @Transactional
        @Test
        void persistsProduct_whenReloadedAfterFlushAndClear() {
            // arrange
            Product saved = productRepository.save(new Product(1L, "루퍼스 티셔츠", new Price(1000L)));

            // act
            entityManager.flush();
            entityManager.clear();
            Product found = productRepository.findById(saved.getId()).orElseThrow();

            // assert
            assertThat(found.getBrandId()).isEqualTo(1L);
            assertThat(found.getName()).isEqualTo("루퍼스 티셔츠");
            assertThat(found.getPrice()).isEqualTo(new Price(1000L));
            assertThat(found.getStock().getQuantity()).isZero();
        }

        @DisplayName("가격을 수정한 뒤 flush·clear 하고 다시 조회하면, 수정된 가격이 조회된다.")
        @Transactional
        @Test
        void persistsChangedPrice_whenReloadedAfterFlushAndClear() {
            // arrange
            Product saved = productRepository.save(new Product(1L, "루퍼스 티셔츠", new Price(1000L)));

            // act
            saved.changePrice(new Price(2000L));
            entityManager.flush();
            entityManager.clear();
            Product found = productRepository.findById(saved.getId()).orElseThrow();

            // assert
            assertThat(found.getPrice()).isEqualTo(new Price(2000L));
        }

        @DisplayName("재고를 변경한 뒤 flush·clear 하고 다시 조회하면, 변경된 수량이 조회된다.")
        @Transactional
        @Test
        void persistsChangedStock_whenReloadedAfterFlushAndClear() {
            // arrange
            Product saved = productRepository.save(new Product(1L, "루퍼스 티셔츠", new Price(1000L)));

            // act
            saved.changeStock(7);
            entityManager.flush();
            entityManager.clear();
            Product found = productRepository.findById(saved.getId()).orElseThrow();

            // assert
            assertThat(found.getStock().getQuantity()).isEqualTo(7);
        }
    }

    @DisplayName("상품 단건을 조회할 때, ")
    @Nested
    class FindById {
        @DisplayName("존재하지 않는 식별자면, 빈 결과가 반환된다.")
        @Transactional
        @Test
        void returnsEmpty_whenProductIsAbsent() {
            // act
            Optional<Product> found = productRepository.findById(-1L);

            // assert
            assertThat(found).isEmpty();
        }
    }

    @Disabled("구현 전 - TDD Red 단계에서 기대값을 채운다.")
    @DisplayName("상품 목록을 조회할 때, ")
    @Nested
    class FindAll {
        @DisplayName("삭제된 상품은 조회 결과에서 제외된다.")
        @Test
        void excludesDeletedProducts() {
        }

        @DisplayName("브랜드로 필터하면, 해당 브랜드의 상품만 조회된다.")
        @Test
        void filtersByBrandId() {
        }

        @DisplayName("정렬 조건(latest/price_asc/likes_desc)대로 정렬되어 조회된다.")
        @Test
        void sortsBySortCondition() {
        }

        @DisplayName("정렬 기준이 동률이면, 보조 정렬 기준으로 순서가 결정된다.")
        @Test
        void appliesSecondarySort_whenPrimarySortIsTied() {
        }
    }

}
