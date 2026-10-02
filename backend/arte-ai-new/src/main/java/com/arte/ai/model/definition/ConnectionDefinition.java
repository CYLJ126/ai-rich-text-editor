package com.arte.ai.model.definition;

import com.arte.base.model.security.SecretRef;

import java.net.URI;

/**
 * 连接配置的版本快照；地址仅属于受控配置，调用请求不接受任意远端地址。
 *
 * <p>顶层契约声明；字段约束、校验、持久化映射及行为在详细设计阶段补充。
 */
public record ConnectionDefinition(
        DefinitionRef ref,
        String providerId,
        String protocol,
        URI endpoint,
        SecretRef secretRef,
        DefinitionStatus status
) {
}
