package com.arte.core.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

/**
 * 状态
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/2/5 21:21 ✾
 */
@Getter
public enum StatusEnum implements IEnum<Integer>, MyEnum<Integer> {
    INITIAL(0, "初始"),
    DOING(1, "正常，启用，进行中"),
    DONE(2, "完成"),
    CLOSED(3, "关闭，禁用，注销"),
    ;

    private final int value;

    private final String description;

    StatusEnum(int value, String description) {
        this.value = value;
        this.description = description;
    }

    public static boolean isDone(StatusEnum status) {
        return status == DONE;
    }

    public static StatusEnum fromValue(int value) {
        for (StatusEnum status : StatusEnum.values()) {
            if (status.value == value) {
                return status;
            }
        }
        throw new IllegalArgumentException("status 参数非法：" + value);
    }

    public static boolean isNormal(StatusEnum status) {
        return status == DOING;
    }

    @Override
    public Integer getValue() {
        return this.value;
    }

    @ReadingConverter
    public enum IntegerToStatusEnumConverter implements Converter<Integer, StatusEnum> {
        INSTANCE;

        @Override
        public StatusEnum convert(Integer source) {
            return fromValue(source);
        }
    }

    @WritingConverter
    public enum StatusEnumToIntegerConverter implements Converter<StatusEnum, Integer> {
        INSTANCE;

        @Override
        public Integer convert(StatusEnum source) {
            return source.getValue();
        }
    }
}
