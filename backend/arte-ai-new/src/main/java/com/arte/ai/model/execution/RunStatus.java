package com.arte.ai.model.execution;

/**
 * 多步编排 Run 状态；由选定运行时管理，独立于公共 Job 状态。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public enum RunStatus {
    READY,
    RUNNING,
    WAITING_EXTERNAL,
    WAITING_USER,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
