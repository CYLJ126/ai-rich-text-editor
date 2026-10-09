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
