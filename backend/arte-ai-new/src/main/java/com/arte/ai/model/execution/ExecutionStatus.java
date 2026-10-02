package com.arte.ai.model.execution;

/**
 * AI Invocation 和 Attempt 的执行状态；调用成功不表示业务应用完成。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public enum ExecutionStatus {
    ACCEPTED,
    RUNNING,
    SUCCEEDED,
    FAILED,
    INTERRUPTED,
    TIMED_OUT,
    OUTCOME_UNKNOWN,
    CANCELLED
}
