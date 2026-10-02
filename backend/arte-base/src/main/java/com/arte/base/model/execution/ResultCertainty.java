package com.arte.base.model.execution;

/**
 * 操作结果的确定性；结果未知时不能盲目重试。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public enum ResultCertainty {
    CONFIRMED,
    UNKNOWN
}
