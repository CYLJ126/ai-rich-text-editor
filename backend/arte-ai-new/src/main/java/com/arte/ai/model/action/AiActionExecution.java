package com.arte.ai.model.action;

import com.arte.ai.model.definition.DefinitionRef;
import com.arte.base.model.resource.ResourceRef;

/**
 * 独立动作的执行记录快照；可关联追问，业务采纳状态由应用记录引用。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record AiActionExecution(
        String actionExecutionId,
        DefinitionRef actionRef,
        String contextSnapshotId,
        String executionId,
        ResourceRef applicationRecordRef
) {
}
