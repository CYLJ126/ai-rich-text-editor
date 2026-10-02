package com.arte.ai.model.definition;

/**
 * 版本化配置引用；定义类型保持可扩展，不以 Java 继承树混合不同语义。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record DefinitionRef(
        String definitionType,
        String definitionId,
        String version
) {
}
