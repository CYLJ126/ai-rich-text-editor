package com.arte.ai.common.enums.tool;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 工具集群变更事件类型。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
@Getter
public enum ToolClusterEventTypeEnum implements IEnum<String>, MyEnum<String> {
    PROVIDER_SYNCED("provider-synced", "工具提供者已同步"),
    PROVIDER_ENABLED("provider-enabled", "工具提供者已启用"),
    PROVIDER_DISABLED("provider-disabled", "工具提供者已禁用"),
    TOOL_PUBLISHED("tool-published", "工具版本已发布"),
    TOOL_DEPRECATED("tool-deprecated", "工具版本已废弃"),
    TOOL_DISABLED("tool-disabled", "工具版本已禁用"),
    CATALOG_CHANGED("catalog-changed", "工具目录已变更"),
    ;

    private final String value;
    private final String description;

    ToolClusterEventTypeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
