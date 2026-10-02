package com.arte.ai.model.remote;

/**
 * 远端任务状态，与本地调用及工作项分别管理。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public enum RemoteTaskStatus {
    ACCEPTED,
    RUNNING,
    WAITING_INPUT,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    UNKNOWN
}
