package com.arte.base.model.execution;

/**
 * 失败或中断后副作用是否已发生的事实状态，与能力声明的风险等级分开。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public enum SideEffectStatus {
    NONE,
    OCCURRED,
    UNKNOWN
}
