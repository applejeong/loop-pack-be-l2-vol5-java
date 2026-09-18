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

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @InjectMocks
    private ProductService productService;

    @Disabled("구현 전 - TDD Red 단계에서 기대값을 채운다.")
    @DisplayName("상품 단건을 조회할 때, ")
    @Nested
    class GetProduct {
        @DisplayName("존재하는 상품이면, 해당 상품을 반환한다.")
        @Test
        void returnsProduct_whenProductExists() {
        }

        @DisplayName("존재하지 않는 상품이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenProductIsAbsent() {
        }

        @DisplayName("삭제된 상품이면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenProductIsDeleted() {
        }
    }

    @Disabled("구현 전 - TDD Red 단계에서 기대값을 채운다.")
    @DisplayName("상품 목록을 조회할 때, ")
    @Nested
    class GetProducts {
        @DisplayName("삭제된 상품은 목록에서 제외된다.")
        @Test
        void excludesDeletedProducts() {
        }

        @DisplayName("지원하지 않는 정렬값이 주어지면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenSortIsNotSupported() {
        }

        @DisplayName("정렬 기준이 동률이면, 보조 정렬 기준이 적용된다.")
        @Test
        void appliesSecondarySort_whenPrimarySortIsTied() {
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
