package com.arte.ai.common.enums.tool;

import com.arte.core.enums.MyEnum;
import com.baomidou.mybatisplus.annotation.IEnum;
import lombok.Getter;

/**
 * 工具提供者类型
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 **/
@Getter
public enum ToolProviderTypeEnum implements IEnum<String>, MyEnum<String> {

    LOCAL("local", "本地 Java 工具"),
    HTTP("http", "HTTP 远程工具"),
    OPENAPI("openapi", "OpenAPI 远程工具"),
    MCP("mcp", "MCP 远程工具"),
    AGENT("agent", "Agent 工具"),
    ;

    private final String value;
    private final String description;

    ToolProviderTypeEnum(String value, String description) {
        this.value = value;
        this.description = description;
    }
}
