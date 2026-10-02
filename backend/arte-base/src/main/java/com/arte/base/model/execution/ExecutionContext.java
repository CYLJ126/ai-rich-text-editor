package com.arte.base.model.execution;

import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.validation.ContractChecks;

import java.time.Instant;
import java.util.Set;

/**
 * 显式传递的执行上下文，不依赖 ThreadLocal。服务端须以已认证主体及已验证作用域构建。
 * scope、traceId、authorizationScopes 必填；空范围集合表示没有声明的任务授权，其他字段可为空。
 * authorizationScopes 防御性复制；其内容不能替代实时资源授权及外发策略检查。
 * deadline 可为空；允许加载已过期的历史上下文，执行准入时显式检查 isExpiredAt。
 */
public record ExecutionContext(
        ExecutionScope scope,
        String traceId,
        String parentExecutionId,
        Instant deadline,
        CancellationRef cancellation,
        Set<String> authorizationScopes,
        ResourceRef budgetRef,
        ResourceRef releaseRef,
        IdempotencyKey idempotencyKey
) {

    public ExecutionContext {
        scope = ContractChecks.required(scope, "scope");
        traceId = ContractChecks.identifier(traceId, "traceId");
        parentExecutionId = ContractChecks.optionalIdentifier(parentExecutionId, "parentExecutionId");
        authorizationScopes = ContractChecks.identifiers(authorizationScopes, "authorizationScopes");
    }

    /**
     * 最小上下文没有隐式身份、授权、预算、期限或发布配置。
     */
    public static ExecutionContext create(ExecutionScope scope, String traceId, Set<String> authorizationScopes) {
        return new ExecutionContext(scope, traceId, null, null, null, authorizationScopes, null, null, null);
    }

    /**
     * 使用调用方提供的时间；达到截止时刻即过期，不在构造或重放时读取系统时钟。
     */
    public boolean isExpiredAt(Instant now) {
        ContractChecks.required(now, "now");
        return deadline != null && !now.isBefore(deadline);
    }
}
