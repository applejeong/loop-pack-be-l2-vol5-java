package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    @DisplayName("상품 단건을 조회할 때, ")
    @Nested
    class GetProduct {
        @DisplayName("존재하는 상품이면, 해당 상품을 반환한다.")
        @Test
        void returnsProduct_whenProductExists() {
            // arrange
            Product product = new Product(1L, "루퍼스 티셔츠", new Price(1000L));
            given(productRepository.findById(1L)).willReturn(Optional.of(product));

            // act
            Product result = productService.getActiveProduct(1L);

            // assert
            assertThat(result.getName()).isEqualTo("루퍼스 티셔츠");
        }

        @DisplayName("존재하지 않는 상품이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenProductIsAbsent() {
            // arrange
            given(productRepository.findById(1L)).willReturn(Optional.empty());

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                productService.getActiveProduct(1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("삭제된 상품이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenProductIsDeleted() {
            // arrange
            Product deleted = new Product(1L, "루퍼스 티셔츠", new Price(1000L));
            deleted.delete();
            given(productRepository.findById(1L)).willReturn(Optional.of(deleted));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                productService.getActiveProduct(1L);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @DisplayName("상품 목록을 조회할 때, ")
    @Nested
    class GetProducts {
        @DisplayName("해석한 정렬 조건으로 저장소 조회에 위임한다.")
        @Test
        void delegatesToRepository_withResolvedSortType() {
            // arrange
            given(productRepository.findAll(1L, ProductSortType.PRICE_ASC, 0, 20))
                .willReturn(List.of(new Product(1L, "루퍼스 티셔츠", new Price(1000L))));

            // act
            List<Product> result = productService.getProducts(1L, "price_asc", 0, 20);

            // assert
            assertThat(result).hasSize(1);
            verify(productRepository).findAll(1L, ProductSortType.PRICE_ASC, 0, 20);
        }

        @DisplayName("정렬값이 없으면, 최신순으로 조회한다.")
        @Test
        void usesLatest_whenSortIsAbsent() {
            // arrange
            given(productRepository.findAll(null, ProductSortType.LATEST, 0, 20)).willReturn(List.of());

            // act
            productService.getProducts(null, null, 0, 20);

            // assert
            verify(productRepository).findAll(null, ProductSortType.LATEST, 0, 20);
        }

        @DisplayName("지원하지 않는 정렬값이 주어지면, BAD_REQUEST 예외가 발생하고 조회하지 않는다.")
        @Test
        void throwsBadRequestException_whenSortIsNotSupported() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                productService.getProducts(null, "price_desc", 0, 20);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            verify(productRepository, never()).findAll(any(), any(), anyInt(), anyInt());
        }
    }

    @Disabled("구현 전 - TDD Red 단계에서 기대값을 채운다.")
    @DisplayName("상품을 등록할 때, ")
    @Nested
    class CreateProduct {
        @DisplayName("이름과 가격이 유효하면, 상품이 저장된다.")
        @Test
        void savesProduct_whenNameAndPriceAreValid() {
        }

        @DisplayName("이름이나 가격이 유효하지 않으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenNameOrPriceIsInvalid() {
        }
    }

    @Disabled("구현 전 - TDD Red 단계에서 기대값을 채운다.")
    @DisplayName("상품을 수정할 때, ")
    @Nested
    class UpdateProduct {
        @DisplayName("존재하는 상품이면, 수정된 값이 저장된다.")
        @Test
        void savesUpdatedProduct_whenProductExists() {
        }

        @DisplayName("수정해도 상품의 브랜드는 변경되지 않는다.")
        @Test
        void keepsBrand_whenProductIsUpdated() {
        }

        @DisplayName("존재하지 않는 상품이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenProductIsAbsent() {
        }
    }

    @DisplayName("상품 재고를 변경할 때, ")
    @Nested
    class ChangeStock {
        @DisplayName("존재하는 상품이면, 최종 수량으로 설정되도록 재고에 위임한다.")
        @Test
        void delegatesToStock_whenProductExists() {
            // arrange
            Product product = new Product(1L, "루퍼스 티셔츠", new Price(1000L));
            product.changeStock(10);
            given(productRepository.findById(1L)).willReturn(Optional.of(product));

            // act
            Product result = productService.changeStock(1L, 3);

            // assert
            assertThat(result.getStock().getQuantity()).isEqualTo(3);
        }

        @DisplayName("음수 수량이 주어지면, BAD_REQUEST 예외가 발생하고 기존 재고가 유지된다.")
        @Test
        void throwsBadRequestExceptionAndKeepsStock_whenQuantityIsNegative() {
            // arrange
            Product product = new Product(1L, "루퍼스 티셔츠", new Price(1000L));
            product.changeStock(10);
            given(productRepository.findById(1L)).willReturn(Optional.of(product));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                productService.changeStock(1L, -1);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
            assertThat(product.getStock().getQuantity()).isEqualTo(10);
        }

        @DisplayName("존재하지 않는 상품이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenProductIsAbsent() {
            // arrange
            given(productRepository.findById(1L)).willReturn(Optional.empty());

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                productService.changeStock(1L, 3);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("삭제된 상품이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenProductIsDeleted() {
            // arrange
            Product deleted = new Product(1L, "루퍼스 티셔츠", new Price(1000L));
            deleted.delete();
            given(productRepository.findById(1L)).willReturn(Optional.of(deleted));

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                productService.changeStock(1L, 3);
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }

    @Disabled("구현 전 - TDD Red 단계에서 기대값을 채운다.")
    @DisplayName("상품을 삭제할 때, ")
    @Nested
    class DeleteProduct {
        @DisplayName("존재하는 상품이면, 삭제 처리된다.")
        @Test
        void deletesProduct_whenProductExists() {
        }

        @DisplayName("존재하지 않는 상품이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenProductIsAbsent() {
        }
    }
}
