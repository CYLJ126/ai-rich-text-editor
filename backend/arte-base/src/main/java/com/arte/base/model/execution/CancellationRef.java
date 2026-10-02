package com.arte.base.model.execution;

/**
 * 取消信号的权威执行引用；不以实例内存标志作为分布式取消事实。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record CancellationRef(
        String executionId
) {
}
