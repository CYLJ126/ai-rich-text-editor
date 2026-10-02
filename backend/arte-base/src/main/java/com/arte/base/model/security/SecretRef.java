package com.arte.base.model.security;

/**
 * 加密凭据的引用；不携带明文凭据，实际解析归连接及基础设施实现。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record SecretRef(
        String secretId,
        String version
) {
}
