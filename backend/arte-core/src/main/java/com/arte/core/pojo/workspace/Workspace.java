package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.WorkspaceStateEnum;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;
import com.arte.core.utils.platform.PlatformTimePrecision;

import java.time.Instant;
import java.util.Objects;

/**
 * 资源管理范围。负责人取有效 OWNER 成员，不重复保存 owner 字段，createdBy 仅为审计。
 * scope 与 createdAt 创建后不可变，作为候选扫描的稳定定位键。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record Workspace(
        Scope scope,
        String name,
        String description,
        WorkspaceStateEnum state,
        String createdBy,
        long version,
        long authEpoch,
        Instant createdAt,
        Instant updatedAt) {
    public Workspace {
        Objects.requireNonNull(scope, "scope");
        name = PlatformChecks.name(name);
        description = PlatformChecks.description(description);
        Objects.requireNonNull(state, "state");
        PlatformChecks.id(createdBy, "createdBy");
        PlatformChecks.version(version, "version");
        PlatformChecks.version(authEpoch, "authEpoch");
        createdAt = PlatformTimePrecision.micros(createdAt);
        updatedAt = PlatformTimePrecision.micros(updatedAt);
        PlatformChecks.require(!updatedAt.isBefore(createdAt), "update precedes creation");
    }
}
