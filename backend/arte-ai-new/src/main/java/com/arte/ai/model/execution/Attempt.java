package com.arte.ai.model.execution;

import com.arte.ai.model.remote.RemoteTaskRef;
import com.arte.base.model.execution.ExecutionError;

/**
 * 一次调用尝试的记录快照；重新生成产生新的尝试，结果未知须核对副作用。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record Attempt(
        String attemptId,
        String invocationId,
        int attemptNumber,
        ExecutionStatus status,
        RemoteTaskRef remoteTask,
        ExecutionError error
) {
}
