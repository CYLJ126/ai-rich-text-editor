package com.arte.core.enums;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.core.convert.support.DefaultConversionService;

import static org.junit.jupiter.api.Assertions.*;

class MyEnumConverterTest {

    private DefaultConversionService conversionService;

    @BeforeEach
    void setUp() {
        conversionService = new DefaultConversionService();
        conversionService.addConverter(new MyEnumConverter());
    }

    @Test
    void shouldConvertStringValueToEnum() {
        assertEquals(StringValueEnum.PUBLISHED,
                conversionService.convert("published", StringValueEnum.class));
    }

    @Test
    void shouldConvertNumericStringToIntegerValueEnum() {
        assertEquals(IntegerValueEnum.ENABLED,
                conversionService.convert("1", IntegerValueEnum.class));
    }

    @Test
    void shouldConvertIntegerValueToEnum() {
        assertEquals(IntegerValueEnum.ENABLED,
                conversionService.convert(1, IntegerValueEnum.class));
    }

    @Test
    void shouldReturnNullForBlankValue() {
        assertNull(conversionService.convert("  ", StringValueEnum.class));
    }

    @Test
    void shouldRejectUnknownValue() {
        assertThrows(ConversionFailedException.class,
                () -> conversionService.convert("missing", StringValueEnum.class));
    }

    private enum StringValueEnum implements MyEnum<String> {
        PUBLISHED("published", "已发布");

        private final String value;
        private final String description;

        StringValueEnum(String value, String description) {
            this.value = value;
            this.description = description;
        }

        @Override
        public String getValue() {
            return value;
        }

        @Override
        public String getDescription() {
            return description;
        }
    }

    private enum IntegerValueEnum implements MyEnum<Integer> {
        ENABLED(1, "启用");

        private final Integer value;
        private final String description;

        IntegerValueEnum(Integer value, String description) {
            this.value = value;
            this.description = description;
        }

        @Override
        public Integer getValue() {
            return value;
        }

        @Override
        public String getDescription() {
            return description;
        }
    }
}
