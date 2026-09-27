package com.arte.ai.api.tool;

import com.arte.ai.pojo.tool.ToolSchema;

/**
 * 工具调用参数和返回值的运行时 Schema 校验端口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
public interface ToolSchemaValidator {

    void validate(ToolSchema schema, Object value, String valueName);
}
