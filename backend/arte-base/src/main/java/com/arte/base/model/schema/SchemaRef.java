package com.arte.base.model.schema;

/**
 * 可版本化的 Schema 引用，不限定具体 Schema 实现库。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record SchemaRef(
        String schemaId,
        String version
) {
}
