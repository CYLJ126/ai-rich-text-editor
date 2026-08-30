package com.arte.core.enums;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import com.arte.core.pojo.DropdownDto;

import java.util.Arrays;
import java.util.List;

/**
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/6/15 16:25 ✾
 */
public interface MyEnum<T> {

    @JsonValue
    T getValue();

    String getDescription();

    @JsonCreator
    static <E extends Enum<E> & MyEnum<T>, T> E fromValue(Class<E> enumClass, T value) {
        if (value == null) {
            return null;
        }

        for (E e : enumClass.getEnumConstants()) {
            if (e.getValue().equals(value)) {
                return e;
            }
        }

        throw new IllegalArgumentException("No enum constant " + enumClass.getCanonicalName() + " for value " + value);
    }

    @JsonCreator
    static <E extends Enum<E> & MyEnum<T>, T> List<DropdownDto> getDropdownOptions(Class<E> enumClass) {
        return Arrays.stream(enumClass.getEnumConstants())
                .map(e -> DropdownDto.of(e.getValue(), e.getDescription()))
                .toList();
    }
}
