package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.TenantStateEnum;
import com.arte.core.enums.workspace.TenantTypeEnum;
import com.arte.core.utils.platform.PlatformChecks;
import com.arte.core.utils.platform.PlatformTimePrecision;

import java.time.Instant;
import java.util.Objects;

/**
 * 租户实体对象
 * <p>
 * 个人托管关联不可按名字重绑，不推断资源读取权限。
 *
 * @param id                租户唯一标识，用于定位治理、政策和隔离边界。
 * @param name              租户展示名称；去除首尾空白后须为 1～100 个字符。
 * @param type              租户类型，不能为空，区分个人托管租户与组织租户。
 * @param personalSubjectId 个人托管租户关联的稳定主体标识；PERSONAL 类型必填，组织租户必须为空。
 * @param state             租户当前状态，不能为空，包括正常、停用和关闭处理中。
 * @param createdBy         创建租户的主体标识，用于记录创建审计事实。
 * @param version           非负的租户记录版本，用于并发更新时的版本校验。
 * @param authEpoch         非负的租户授权纪元（版本号）；租户授权依据变更时推进，用于识别授权快照是否过期。
 * @param createdAt         创建时间，不能为空，进入模型时截断为微秒精度。
 * @param updatedAt         最近更新时间，不能为空，进入模型时截断为微秒精度，且不得早于创建时间。
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
