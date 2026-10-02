package com.arte.ai.model.execution;

/**
 * 声明支持的任务控制动作；支持取消不意味着支持暂停或继续。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public enum ControlAction {
    CANCEL,
    PAUSE,
    RESUME
}
