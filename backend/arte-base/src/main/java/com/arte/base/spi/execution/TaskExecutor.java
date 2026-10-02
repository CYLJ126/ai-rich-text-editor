package com.arte.base.spi.execution;

import com.arte.base.execution.TaskHandle;
import com.arte.base.model.execution.ExecutionContext;

/**
 * 本地执行端口，不承担可靠受理、跨节点派发、预算或业务状态存储。
 */
public interface TaskExecutor {
    <T> TaskHandle<T> submit(ExecutionContext context, ExecutionTask<T> task);
}
