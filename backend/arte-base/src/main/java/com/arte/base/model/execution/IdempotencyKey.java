package com.arte.base.model.execution;

/**
 * 按主体、操作和请求摘要限定的幂等标识；执行与业务应用分别管理。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record IdempotencyKey(
        String key,
        String operation,
        String requestDigest
) {
}
