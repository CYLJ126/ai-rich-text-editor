package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.ClosureWorkStateEnum;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 固定目标关闭工作项；唯一(operationId,domain,workKey)，领取／续租使用版本及 fencing。
 *
 * @param operationId 对应的关闭操作 ID
 * @param scope       目标空间范围，包含租户 ID 和空间 ID
 * @param domain      负责处理该工作项的业务领域标识
 * @param workKey     关闭操作中该领域下的工作项标识
 * @param targetRefs  已为该工作项登记的固定清理目标引用集合
 * @param state       工作项当前状态
 * @param version     工作项记录版本，用于并发更新校验
 * @param leaseOwner  当前租约持有者标识，无租约时为 {@code null}
 * @param fencing     当前租约的防护令牌，用于识别过期持有者，无租约时为 {@code null}
 * @param leaseUntil  当前租约的到期时间，无租约时为 {@code null}
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ClosureWorkItem(
        String operationId,
        Scope scope,
        String domain,
        String workKey,
        Set<String> targetRefs,
        ClosureWorkStateEnum state,
        long version,
        String leaseOwner,
        Long fencing,
        Instant leaseUntil) {

    public ClosureWorkItem {
        PlatformChecks.id(operationId, "operationId");
        Objects.requireNonNull(scope, "scope");
        PlatformChecks.token(domain, "domain");
        PlatformChecks.id(workKey, "workKey");
        targetRefs = Set.copyOf(targetRefs);
        Objects.requireNonNull(state, "state");
        PlatformChecks.version(version, "version");
        if (leaseOwner != null) PlatformChecks.id(leaseOwner, "leaseOwner");
        if (fencing != null) PlatformChecks.version(fencing, "fencing");
        targetRefs.forEach(v -> PlatformChecks.id(v, "targetRef"));
        PlatformChecks.require((leaseOwner != null) == (leaseUntil != null) && (leaseOwner != null) == (fencing != null), "lease fields must be present together");
    }
}
