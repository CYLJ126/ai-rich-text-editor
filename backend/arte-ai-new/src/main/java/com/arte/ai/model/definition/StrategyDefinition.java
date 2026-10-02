package com.arte.ai.model.definition;

import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.schema.SchemaRef;

/**
 * 可插拔策略定义；策略类别和配置 Schema 分别声明。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record StrategyDefinition(
        DefinitionRef ref,
        String strategyType,
        SchemaRef configurationSchema,
        ResourceRef configurationRef,
        DefinitionStatus status
) {
}
