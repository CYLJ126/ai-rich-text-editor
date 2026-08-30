package com.arte.core.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;

/**
 * 是否、开关
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2025/2/5 21:21 ✾
 */
@Getter
public enum YesOrNoEnum implements IEnum<Integer>, MyEnum<Integer> {
    // 不能设置为 false 和 true，不然前端转换器报错
    YES(1, "是"),
    NO(0, "否"),
    ;

    private final Integer value;
    private final String description;

    YesOrNoEnum(Integer value, String description) {
        this.value = value;
        this.description = description;
    }

    public static boolean isYes(YesOrNoEnum status) {
        return status == YES;
    }

    public static boolean isNo(YesOrNoEnum status) {
        return status == NO;
    }

    @Override
    public Integer getValue() {
        return this.value;
    }


    public static YesOrNoEnum fromValue(int value) {
        for (YesOrNoEnum yesOrNoEnum : YesOrNoEnum.values()) {
            if (yesOrNoEnum.value == value) {
                return yesOrNoEnum;
            }
        }
        throw new IllegalArgumentException("yesOrNoEnum 参数非法：" + value);
    }

    @ReadingConverter
    public enum IntegerToYesOrNoEnumConverter implements Converter<Integer, YesOrNoEnum> {
        INSTANCE;

        @Override
        public YesOrNoEnum convert(Integer source) {
            return fromValue(source);
        }
    }

    @WritingConverter
    public enum YesOrNoEnumToIntegerConverter implements Converter<YesOrNoEnum, Integer> {
        INSTANCE;

        @Override
        public Integer convert(YesOrNoEnum source) {
            return source.getValue();
        }
    }
}
