package com.arte.ai.common.enums.tool;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 工具生命周期管理状态
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 19:01 ✾
 **/
@Getter
public enum ToolLifecycleStateEnum implements IEnum<String>, MyEnum<String> {
    DRAFT("draft", "草稿"),
    PUBLISHED("published", "已发布"),
    DEPRECATED("deprecated", "已废弃"),
    DISABLED("disabled", "已禁用"),
    ;

    private final String value;
    private final String description;

    ToolLifecycleStateEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
