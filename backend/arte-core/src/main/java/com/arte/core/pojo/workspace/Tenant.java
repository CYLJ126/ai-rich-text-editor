package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.TenantStateEnum;
import com.arte.core.enums.workspace.TenantTypeEnum;
import com.arte.core.utils.platform.PlatformChecks;
import com.arte.core.utils.platform.PlatformTimePrecision;

import java.time.Instant;
import java.util.Objects;

/**
 * 租户治理边界；个人托管关联不可按名字重绑，不推断资源读取权限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record Tenant(
        String id,
        String name,
        TenantTypeEnum type,
        String personalSubjectId,
        TenantStateEnum state,
        String createdBy,
        long version,
        long authEpoch,
        Instant createdAt,
        Instant updatedAt) {
    public Tenant {
        PlatformChecks.id(id, "id");
        name = PlatformChecks.name(name);
        Objects.requireNonNull(type, "type");
        if (personalSubjectId != null) PlatformChecks.id(personalSubjectId, "personalSubjectId");
        Objects.requireNonNull(state, "state");
        PlatformChecks.id(createdBy, "createdBy");
        PlatformChecks.version(version, "version");
        PlatformChecks.version(authEpoch, "authEpoch");
        createdAt = PlatformTimePrecision.micros(createdAt);
        updatedAt = PlatformTimePrecision.micros(updatedAt);
        PlatformChecks.require((type == TenantTypeEnum.PERSONAL) == (personalSubjectId != null), "personal subject must match tenant type");
        PlatformChecks.require(!updatedAt.isBefore(createdAt), "update precedes creation");
    }
}
