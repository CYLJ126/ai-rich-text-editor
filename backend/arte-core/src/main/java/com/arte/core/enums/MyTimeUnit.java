package com.arte.core.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

import java.util.regex.Pattern;

/**
 * 时间单位枚举
 *
 * @author zhangsc
 * @since 2025/9/27 15:03
 */
@Getter
public enum MyTimeUnit implements IEnum<String>, MyEnum<String> {
    YEAR("y", "第%d年", "年"), // 1 年 有 4 季，12 个月，52 周，365 天
    QUARTER("q", "第%d季", "季"), // 1 季 有 3 个月，13 周，91 天
    MONTH("m", "第%d月", "月"), // 1 月 有 4 周，30 天
    WEEK("w", "第%d周", "周"), // 1 周 有 7 天
    DAY("d", "第%d天", "天"), // 1 天 有 24 小时
    HOUR("h", "第%d小时", "小时"), // 1 小时 有 60 分钟
    MINUTE("min", "第%d分钟", "分钟"), // 1 分钟 有 60 秒
    SECOND("s", "第%d秒", "秒"), // 1 秒 有 1000 毫秒
    MILLISECOND("ms", "第%d毫秒", "毫秒"), // 1 毫秒 有 1000 微秒
    MICROSECOND("us", "第%d微秒", "微秒"), // 1 微秒 有 1000 纳秒
    NANOSECOND("ns", "第%d纳秒", "纳秒"), // 1 纳秒 有 1000 秒
    ;

    /**
     * 时间单位匹配的正则表达式，按照优先级排序（长的在前面避免被短的误匹配）
     */
    public static final Pattern TIME_PATTERN = Pattern.compile(
            "-?(\\d+(?:\\.\\d+)?)(ms|min|us|ns|[yqmdhws])"
    );

    private final String value;
    private final String format;
    private final String description;

    MyTimeUnit(String value, String format, String description) {
        this.value = value;
        this.format = format;
        this.description = description;
    }

    /**
     * 转换时间单位
     * 涉及月、季、年的转换，存在误差
     *
     * @param from  原时间单位
     * @param to    待时间单位
     * @param value 时间值
     * @return 转换后的时间值
     */
    public static double convert(MyTimeUnit from, MyTimeUnit to, double value) {
        if (from == to) {
            return value;
        }

        // 先转换为纳秒作为基准单位
        double nanoseconds = convertToNanoseconds(from, value);
        // 再从纳秒转换为目标单位
        return convertFromNanoseconds(to, nanoseconds);
    }

    /**
     * 将指定单位的值转换为纳秒
     */
    private static double convertToNanoseconds(MyTimeUnit unit, double value) {
        return switch (unit) {
            case YEAR -> value * 365 * 24 * 60 * 60 * 1_000_000_000L; // 按365天计算
            case QUARTER -> value * 91 * 24 * 60 * 60 * 1_000_000_000L; // 按91天计算
            case MONTH -> value * 30 * 24 * 60 * 60 * 1_000_000_000L; // 按30天计算
            case WEEK -> value * 7 * 24 * 60 * 60 * 1_000_000_000L;
            case DAY -> value * 24 * 60 * 60 * 1_000_000_000L;
            case HOUR -> value * 60 * 60 * 1_000_000_000L;
            case MINUTE -> value * 60 * 1_000_000_000L;
            case SECOND -> value * 1_000_000_000L;
            case MILLISECOND -> value * 1_000_000L;
            case MICROSECOND -> value * 1_000L;
            case NANOSECOND -> value;
        };
    }

    /**
     * 将纳秒转换为指定单位的值
     */
    private static double convertFromNanoseconds(MyTimeUnit unit, double nanoseconds) {
        return switch (unit) {
            case YEAR -> nanoseconds / (365 * 24 * 60 * 60 * 1_000_000_000L); // 按 365 天计算
            case QUARTER -> nanoseconds / (91 * 24 * 60 * 60 * 1_000_000_000L); // 按 91 天计算
            case MONTH -> nanoseconds / (30 * 24 * 60 * 60 * 1_000_000_000L); // 按 30 天计算
            case WEEK -> nanoseconds / (7 * 24 * 60 * 60 * 1_000_000_000L);
            case DAY -> nanoseconds / (24 * 60 * 60 * 1_000_000_000L);
            case HOUR -> nanoseconds / (60 * 60 * 1_000_000_000L);
            case MINUTE -> nanoseconds / (60 * 1_000_000_000L);
            case SECOND -> nanoseconds / 1_000_000_000L;
            case MILLISECOND -> nanoseconds / 1_000_000L;
            case MICROSECOND -> nanoseconds / 1_000L;
            case NANOSECOND -> nanoseconds;
        };
    }
}