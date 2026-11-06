package com.arte.core.pojo.access;

import com.arte.core.enums.access.GrantTargetKindEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.pojo.platform.CommandScope;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 授权目标；SUBJECT 绑定当次成员代次，角色／空间共享面向当前有效成员。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public sealed interface GrantTarget permits GrantTarget.Subject, GrantTarget.SpaceRole, GrantTarget.Space {

    GrantTargetKindEnum kind();

    /**
     * scope 必须至少含租户；workspace 为空时绑定租户成员代次。
     *
     * @param subjectId 获授权的主体 ID
     * @param scope 授权绑定范围，空间 ID 为空时绑定租户成员关系
     * @param membershipGeneration 授权绑定的成员代次，用于区分同一主体的不同入会周期
     */
    record Subject(String subjectId, CommandScope scope, long membershipGeneration) implements GrantTarget {

        public Subject {
            PlatformChecks.id(subjectId, "subjectId");
            Objects.requireNonNull(scope);
            PlatformChecks.require(scope.tenantId() != null, "subject grant needs tenant");
            PlatformChecks.version(membershipGeneration, "membershipGeneration");
        }

        @Override
        public GrantTargetKindEnum kind() {
            return GrantTargetKindEnum.SUBJECT;
        }
    }

    /**
     * 将授权绑定到指定空间中当前持有指定角色的有效成员。
     *
     * @param scope 获授权角色所属的空间范围
     * @param role  获授权的空间角色
     */
    record SpaceRole(Scope scope, WorkspaceRoleEnum role) implements GrantTarget {
        public SpaceRole {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(role);
        }

        @Override
        public GrantTargetKindEnum kind() {
            return GrantTargetKindEnum.SPACE_ROLE;
        }
    }

    /**
     * 将授权绑定到指定空间的当前有效成员。
     *
     * @param scope 获授权的空间范围
     */
    record Space(Scope scope) implements GrantTarget {
        public Space {
            Objects.requireNonNull(scope);
        }

        @Override
        public GrantTargetKindEnum kind() {
            return GrantTargetKindEnum.WORKSPACE;
        }
    }
}
