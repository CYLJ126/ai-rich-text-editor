package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.CleanupAuthorizationStateEnum;
import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 后台受限授权，不含新生成／未知调用重放权。目标及动作由受信治理服务固定，Worker 不能自行扩展。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record CleanupAuthorization(
        String id,
        String actorSubjectId,
        Scope scope,
        String governanceOperationId,
        String domain,
        Set<Action> actions,
        String workKey,
        Set<String> targetRefs,
        Instant expiresAt,
        CleanupAuthorizationStateEnum state,
        long version) {
    public CleanupAuthorization {
        PlatformChecks.id(id, "id");
        PlatformChecks.id(actorSubjectId, "actorSubjectId");
        Objects.requireNonNull(scope, "scope");
        PlatformChecks.id(governanceOperationId, "governanceOperationId");
        PlatformChecks.token(domain, "domain");
        actions = Set.copyOf(actions);
        PlatformChecks.id(workKey, "workKey");
        targetRefs = Set.copyOf(targetRefs);
        Objects.requireNonNull(expiresAt, "expiresAt");
        Objects.requireNonNull(state, "state");
        PlatformChecks.version(version, "version");
        PlatformChecks.require(!actions.isEmpty() && !targetRefs.isEmpty(), "cleanup requires fixed actions and targets");
        targetRefs.forEach(v -> PlatformChecks.id(v, "targetRef"));
    }
}
