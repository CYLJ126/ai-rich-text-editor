package com.arte.core.pojo.access;

import com.arte.core.enums.access.GrantSourceEnum;
import com.arte.core.enums.access.GrantStateEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 资源授权记录
 * <p>
 * 首版仅 ALLOW；一记录一动作，来源独立、防重不覆盖其他来源，撤销保留历史。
 *
 * @param id                授权记录标识。
 * @param resource          被授权资源的引用，包含租户、资源类型和资源标识。
 * @param target            授权目标：主体、空间角色或整个空间，必须与资源属于同一租户。
 * @param action            本条记录允许的单个资源动作。
 * @param state             授权记录状态：有效或已撤销；实际可用性还需校验期限及目标资格。
 * @param expiresAt         授权到期时间；为空表示不设到期时间，非空时必须晚于创建时间。
 * @param source            授权来源，不同来源的授权独立保存。
 * @param sourceOperationId 产生本条授权的来源操作标识，用于关联来源操作。
 * @param issuedBy          签发本条授权的主体标识。
 * @param createdAt         授权记录创建时间。
 * @param version           授权记录版本号，用于更新时的版本比较与交换（CAS）。
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
