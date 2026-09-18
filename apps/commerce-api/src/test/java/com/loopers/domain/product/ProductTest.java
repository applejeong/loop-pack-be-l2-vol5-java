package com.loopers.domain.product;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ProductTest {
    @DisplayName("상품을 생성할 때, ")
    @Nested
    class Create {
        @DisplayName("브랜드·이름·가격이 모두 주어지면, 정상적으로 생성된다.")
        @Test
        void createsProduct_whenBrandAndNameAndPriceAreProvided() {
            // act
            Product product = new Product(1L, "루퍼스 티셔츠", new Price(1000L));

            // assert
            assertThat(product.getBrandId()).isEqualTo(1L);
            assertThat(product.getName()).isEqualTo("루퍼스 티셔츠");
            assertThat(product.getPrice()).isEqualTo(new Price(1000L));
        }

        @DisplayName("이름이 비어있으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenNameIsBlank() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new Product(1L, "   ", new Price(1000L));
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("이름이 100자를 넘으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenNameLengthExceedsMax() {
            // arrange
            String name = "가".repeat(101);

            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new Product(1L, name, new Price(1000L));
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("가격이 허용 범위를 벗어나면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenPriceIsOutOfRange() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new Product(1L, "루퍼스 티셔츠", new Price(-1L));
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }

        @DisplayName("브랜드 식별자가 없으면, BAD_REQUEST 예외가 발생한다.")
        @Test
        void throwsBadRequestException_whenBrandIdIsNull() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> {
                new Product(null, "루퍼스 티셔츠", new Price(1000L));
            });

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.BAD_REQUEST);
        }
    }

    @DisplayName("상품 가격을 수정할 때, ")
    @Nested
    class ChangePrice {
        @DisplayName("유효한 가격이 주어지면, 해당 가격으로 변경된다.")
        @Test
        void changesPrice_whenPriceIsValid() {
            // arrange
            Product product = new Product(1L, "루퍼스 티셔츠", new Price(1000L));

            // act
            product.changePrice(new Price(2000L));

            // assert
            assertThat(product.getPrice()).isEqualTo(new Price(2000L));
        }

        @DisplayName("가격이 허용 범위를 벗어나면, BAD_REQUEST 예외가 발생하고 기존 가격이 유지된다.")
        @Test
        void keepsPrice_whenPriceIsOutOfRange() {
            // arrange
            Product product = new Product(1L, "루퍼스 티셔츠", new Price(1000L));

            // act
            assertThrows(CoreException.class, () -> {
                product.changePrice(new Price(-1L));
            });

            // assert
            assertThat(product.getPrice()).isEqualTo(new Price(1000L));
        }
    }

    @DisplayName("상품을 삭제할 때, ")
    @Nested
    class Delete {
        @DisplayName("삭제하면, 삭제된 상품으로 식별된다.")
        @Test
        void marksAsDeleted_whenDeleted() {
            // arrange
            Product product = new Product(1L, "루퍼스 티셔츠", new Price(1000L));

            // act
            product.delete();

            // assert
            assertThat(product.getDeletedAt()).isNotNull();
        }

        @DisplayName("생성 직후에는, 삭제되지 않은 상품으로 식별된다.")
        @Test
        void isNotDeleted_whenCreated() {
            // act
            Product product = new Product(1L, "루퍼스 티셔츠", new Price(1000L));

            // assert
            assertThat(product.getDeletedAt()).isNull();
        }
    }
}
