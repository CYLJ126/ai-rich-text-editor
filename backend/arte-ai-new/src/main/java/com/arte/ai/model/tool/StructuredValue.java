package com.arte.ai.model.tool;

import com.arte.base.model.schema.SchemaRef;

/**
 * 带 Schema 引用的结构化值；JSON 在执行边界校验，不作为无约束参数通道。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record StructuredValue(
        SchemaRef schema,
        String json
) {
}
