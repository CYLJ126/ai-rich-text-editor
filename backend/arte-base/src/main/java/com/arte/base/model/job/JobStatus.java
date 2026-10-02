package com.arte.base.model.job;

/**
 * 通用 Job 的权威调度状态，独立于 AI Invocation 和编排 Run。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public enum JobStatus {
    READY,
    RUNNING,
    WAITING_EXTERNAL,
    WAITING_USER,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED,
    DEAD_LETTER
}
