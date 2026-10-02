package com.arte.base.model.identity;

/**
 * 经验证主体的引用，不承载凭据。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record PrincipalRef(
        String principalId,
        PrincipalType type
) {
}
