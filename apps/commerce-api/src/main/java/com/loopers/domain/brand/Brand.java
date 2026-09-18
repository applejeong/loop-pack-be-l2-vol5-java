package com.loopers.domain.brand;

import com.loopers.domain.BaseEntity;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

@Getter
@Entity
@Table(name = "brands")
public class Brand extends BaseEntity {

    public static final int MAX_NAME_LENGTH = 100;

    private String name;

    protected Brand() {}

    public Brand(String name) {
        if (name == null || name.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "브랜드 이름은 비어있을 수 없습니다.");
        }
        if (name.length() > MAX_NAME_LENGTH) {
            throw new CoreException(ErrorType.BAD_REQUEST, "브랜드 이름은 " + MAX_NAME_LENGTH + "자를 넘을 수 없습니다.");
        }
        this.name = name;
    }
}
