package com.arte.core.enums;

import org.jspecify.annotations.NonNull;
import org.springframework.core.convert.TypeDescriptor;
import org.springframework.core.convert.converter.ConditionalGenericConverter;

import java.util.Set;

/**
 * 将字符串或整数转换为实现了 {@link MyEnum} 的枚举。
 *
 * <p>Spring 默认按照枚举常量名称转换，例如字符串只接受 {@code PUBLISHED}。
 * 本转换器改为按照 {@link MyEnum#getValue()} 匹配，因此字符串
 * {@code published} 和整数 {@code 1} 都可以转换为对应的枚举常量。
 * 同时也支持将 Web 请求中的数字字符串（例如 {@code "1"}）转换为
 * value 类型为 {@link Integer} 的枚举。</p>
 *
 * <p>目前仅支持 {@link String} 和 {@link Integer} 两种源类型及 value 类型，
 * 其他类型会明确报错，避免发生不可预期的隐式转换。</p>
 *
 * @author CYLJ126 ≧◔◡◔≦
 */
public class MyEnumConverter implements ConditionalGenericConverter {

    private static final Set<ConvertiblePair> CONVERTIBLE_TYPES = Set.of(
            new ConvertiblePair(String.class, Enum.class),
            new ConvertiblePair(Integer.class, Enum.class)
    );

    @Override
    public Set<ConvertiblePair> getConvertibleTypes() {
        return CONVERTIBLE_TYPES;
    }

    @Override
    public boolean matches(@NonNull TypeDescriptor sourceType, TypeDescriptor targetType) {
        return MyEnum.class.isAssignableFrom(targetType.getType());
    }

    @Override
    public Object convert(Object source, @NonNull TypeDescriptor sourceType, @NonNull TypeDescriptor targetType) {
        if (source == null) {
            return null;
        }
        if (source instanceof String stringSource && stringSource.trim().isEmpty()) {
            return null;
        }
        Class<?> enumType = targetType.getType();
        Object[] constants = enumType.getEnumConstants();
        if (constants == null || constants.length == 0) {
            throw new IllegalArgumentException("Target type is not a valid enum: " + enumType.getCanonicalName());
        }
        Object sampleValue = ((MyEnum<?>) constants[0]).getValue();
        Object convertedValue = convertSourceValue(source, sampleValue, enumType);
        for (Object constant : constants) {
            if (((MyEnum<?>) constant).getValue().equals(convertedValue)) {
                return constant;
            }
        }
        throw new IllegalArgumentException("No enum constant " + enumType.getCanonicalName() + " for value " + source);
    }

    private Object convertSourceValue(Object source, Object sampleValue, Class<?> enumType) {
        if (sampleValue instanceof String) {
            if (source instanceof String stringSource) {
                return stringSource.trim();
            }
            throw unsupportedSourceType(source, sampleValue, enumType);
        }
        if (sampleValue instanceof Integer) {
            if (source instanceof Integer) {
                return source;
            }
            if (source instanceof String stringSource) {
                try {
                    return Integer.valueOf(stringSource.trim());
                } catch (NumberFormatException exception) {
                    throw new IllegalArgumentException(
                            "Value '" + source + "' is not a valid Integer for " + enumType.getCanonicalName(),
                            exception
                    );
                }
            }
            throw unsupportedSourceType(source, sampleValue, enumType);
        }

        throw new IllegalArgumentException(
                "Unsupported MyEnum value type " + sampleValue.getClass().getCanonicalName()
                        + " for " + enumType.getCanonicalName()
        );
    }

    private IllegalArgumentException unsupportedSourceType(
            Object source, Object sampleValue, Class<?> enumType) {
        return new IllegalArgumentException(
                "Cannot convert source type " + source.getClass().getCanonicalName()
                        + " to MyEnum value type " + sampleValue.getClass().getCanonicalName()
                        + " for " + enumType.getCanonicalName()
        );
    }
}
