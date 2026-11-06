package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 空间成员关系。OWNER 交接及唯一性由门控事务保证，角色不自动开放私有正文。
 *
 * @param id 空间成员关系记录 ID
 * @param scope 目标空间范围，包含租户 ID 和空间 ID
 * @param subjectId 成员对应的主体 ID
 * @param role 成员当前的空间角色
 * @param state 成员关系当前状态
 * @param generation 成员资格代次，用于区分重新加入前后的授权绑定
 * @param version 成员关系记录版本，用于并发更新校验
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record WorkspaceMembership(
        String id,
        Scope scope,
        String subjectId,
        WorkspaceRoleEnum role,
        MembershipStateEnum state,
        long generation,
        long version) {

    public WorkspaceMembership {
        PlatformChecks.id(id, "id");
        Objects.requireNonNull(scope, "scope");
        PlatformChecks.id(subjectId, "subjectId");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(state, "state");
        PlatformChecks.version(generation, "generation");
        PlatformChecks.version(version, "version");
    }
}
