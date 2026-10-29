package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.WorkspaceStateEnum;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;
import com.arte.core.utils.platform.PlatformTimePrecision;

import java.time.Instant;
import java.util.Objects;

/**
 * 工作空间实体对象
 * <p>
 * 资源管理范围
 * 负责人取有效 OWNER 成员，不重复保存 owner 字段，createdBy 仅为审计。
 * scope 与 createdAt 创建后不可变，作为候选扫描的稳定定位键。
 *
 * @param scope       空间所属的租户与空间标识，不能为空，创建后不可变。
 * @param name        空间展示名称；去除首尾空白后须为 1～100 个字符。
 * @param description 空间描述，最多 1000 个字符；传入 null 时规范化为空字符串。
 * @param state       空间当前生命周期状态，不能为空，包括正常、已归档、关闭处理中和已软删除。
 * @param createdBy   创建空间的主体标识，用于记录创建审计事实，不随负责人交接而改写。
 * @param version     非负的空间记录版本，用于并发更新时的版本校验。
 * @param authEpoch   非负的空间授权纪元（版本号）；空间授权依据变更时推进，用于识别授权快照是否过期。
 * @param createdAt   创建时间，不能为空，创建后不可变，进入模型时截断为微秒精度。
 * @param updatedAt   最近更新时间，不能为空，进入模型时截断为微秒精度，且不得早于创建时间。
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
