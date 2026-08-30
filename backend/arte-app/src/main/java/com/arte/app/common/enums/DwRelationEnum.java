package com.arte.app.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 日课相关关系类型
 *
 * @author zhangsc
 * @since 2025/2/25 20:33
 */
@Getter
public enum DwRelationEnum implements IEnum<String> {
    TARGET_WEEK("target_week", "目标与周 ID 的对应关系");

    private final String value;
    private final String description;

    DwRelationEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
