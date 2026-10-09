package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.ClosureWorkStateEnum;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 耐久关闭操作，不通过完成时间推断资源已经删除。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ClosureOperation(
        String id,
        Scope scope,
        String requestedBy,
        String reason,
        ClosureWorkStateEnum state,
        long version,
        Instant acceptedAt,
        Instant updatedAt) {
    public ClosureOperation {
        PlatformChecks.id(id, "id");
        Objects.requireNonNull(scope, "scope");
        PlatformChecks.id(requestedBy, "requestedBy");
        reason = PlatformChecks.description(reason);
        Objects.requireNonNull(state, "state");
        PlatformChecks.version(version, "version");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        PlatformChecks.require(!updatedAt.isBefore(acceptedAt), "update precedes acceptance");
    }
}
