package com.arte.ai.pojo.tool;

import java.util.Objects;

/**
 * 工具输入或输出的 Schema。
 * <p>
 * 推荐使用 JSON Schema，它同时用于模型工具定义、运行时校验和页面表单生成。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolSchema(String dialect, String schema) {

    public ToolSchema {
        Objects.requireNonNull(dialect, "dialect");
        Objects.requireNonNull(schema, "schema");
    }
}
