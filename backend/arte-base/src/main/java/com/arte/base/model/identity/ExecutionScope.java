package com.arte.base.model.identity;

/**
 * 租户隔离、工作空间及执行主体范围。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ExecutionScope(
        String tenantId,
        String workspaceId,
        PrincipalRef principal
) {
}
