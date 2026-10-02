package com.arte.ai.model.generation;

/**
 * 模型生成的基础选项；供应商扩展及边界值后续按能力 Schema 细化。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ModelOptions(
        Double temperature,
        Integer maxOutputTokens
) {
}
