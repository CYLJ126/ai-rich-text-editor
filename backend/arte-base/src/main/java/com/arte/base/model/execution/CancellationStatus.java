package com.arte.base.model.execution;

/**
 * 取消请求与取消完成分别表达；已完成表示执行已结束，未承诺被取消。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public enum CancellationStatus {
    REQUEST_ACCEPTED,
    CANCELLING,
    CANCELLED,
    UNCONFIRMED,
    ALREADY_COMPLETED
}
