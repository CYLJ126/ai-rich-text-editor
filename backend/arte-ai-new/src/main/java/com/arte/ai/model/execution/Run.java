package com.arte.ai.model.execution;

import com.arte.ai.model.definition.DefinitionRef;
import com.arte.base.model.execution.ExecutionContext;

/**
 * 多步任务的查询快照；引擎历史存在时不以此快照另行推进状态。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record Run(
        String runId,
        String runtimeKind,
        DefinitionRef definitionRef,
        ExecutionContext context,
        RunStatus status
) {
}
