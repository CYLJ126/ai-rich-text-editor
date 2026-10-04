package com.arte.ainew.context;

import java.time.Duration;
import java.util.Objects;
import java.util.Set;

/**
 * 可信入口提供的执行选项；不包含客户端可自报的主体身份。
 * 空间和 scopes 是待验证的请求范围，budgetRef／releaseRef 等引用仍须对应服务校验。
 * 超时应由入口按平台上限约束；幂等键的作用域与请求摘要由受理服务验证。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 20:50 ✾
 */
public record ExecutionContextRequest(
        String tenantId, String workspaceId, Set<String> scopes, Duration timeout,
        String traceId, String budgetRef, String releaseRef, String idempotencyKey) {
    public ExecutionContextRequest {
        if (tenantId == null || tenantId.isBlank() || workspaceId == null || workspaceId.isBlank()) {
            throw new IllegalArgumentException("Tenant and workspace must not be blank");
        }
        scopes = Set.copyOf(Objects.requireNonNull(scopes, "scopes"));
        if (scopes.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Scope must not be blank");
        }
        if (Objects.requireNonNull(timeout, "timeout").isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
    }
}
