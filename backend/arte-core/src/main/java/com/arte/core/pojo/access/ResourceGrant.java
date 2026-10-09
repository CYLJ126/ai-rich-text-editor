package com.arte.core.pojo.access;

import com.arte.core.enums.access.GrantSourceEnum;
import com.arte.core.enums.access.GrantStateEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 首版仅 ALLOW；一记录一动作，来源独立、防重不覆盖其他来源，撤销保留历史。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ResourceGrant(
        String id,
        ResourceRef resource,
        GrantTarget target,
        Action action,
        GrantStateEnum state,
        Instant expiresAt,
        GrantSourceEnum source,
        String sourceOperationId,
        String issuedBy,
        Instant createdAt,
        long version) {
    public ResourceGrant {
        PlatformChecks.id(id, "id");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(source, "source");
        PlatformChecks.id(sourceOperationId, "sourceOperationId");
        PlatformChecks.id(issuedBy, "issuedBy");
        Objects.requireNonNull(createdAt, "createdAt");
        PlatformChecks.version(version, "version");
        PlatformChecks.require(expiresAt == null || expiresAt.isAfter(createdAt), "grant expiry must follow creation");
        String targetTenant = switch (target) {
            case GrantTarget.Subject s -> s.scope().tenantId();
            case GrantTarget.SpaceRole r -> r.scope().tenantId();
            case GrantTarget.Space s -> s.scope().tenantId();
        };
        PlatformChecks.require(resource.tenantId().equals(targetTenant), "cross-tenant grant unsupported");
    }
}
