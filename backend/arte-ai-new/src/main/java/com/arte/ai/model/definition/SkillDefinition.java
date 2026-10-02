package com.arte.ai.model.definition;

import com.arte.base.model.resource.ResourceRef;

/**
 * 受信指令与资源包定义；资源引用不意味着允许执行脚本。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record SkillDefinition(
        DefinitionRef ref,
        ResourceRef packageRef,
        DefinitionStatus status
) {
}
