package com.arte.app.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import com.arte.core.enums.MyEnum;
import lombok.Getter;

/**
 * 总结操作类型枚举
 *
 * @author zhangsc
 * @since 2025/9/27 14:33
 */
@Getter
public enum SummaryOperationTypeEnum implements IEnum<String>, MyEnum<String> {
    FORMAT_SUMMARY("formatSummary", "格式化总结内容"),
    FORMAT_SERIAL_NO("formatSerialNo", "格式化序号"),
    REMOVE_TIME("removeTime", "移除工时"),
    REMOVE_SUB("removeSub", "去除子级"),
    ;

    private final String value;
    private final String description;

    SummaryOperationTypeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
