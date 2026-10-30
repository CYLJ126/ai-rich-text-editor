package com.arte.core.pojo.access;

import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 写命令的期望授权目标
 * <p>
 * 不接受客户端自报成员代次。服务端验证后生成 GrantTarget。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public sealed interface GrantRecipient permits GrantRecipient.Subject, GrantRecipient.SpaceRole, GrantRecipient.Space {

    /**
     * 指定主体的直接授权目标
     * <p>
     * 用于向某个明确的成员授予资源动作，例如只允许某位成员读取或使用模型。
     * 服务端核验成员资格后，将当前成员代次写入 {@link GrantTarget.Subject}，退出后重新加入不会自动恢复旧授权。
     *
     * @param subjectId 接收授权的稳定主体 ID，不使用可变登录名；所属范围和成员代次由服务端根据资源归属及当前成员关系确定。
     */
    record Subject(String subjectId) implements GrantRecipient {
        public Subject {
            PlatformChecks.id(subjectId, "subjectId");
        }
    }

    /**
     * 指定空间角色的共享授权目标
     * <p>
     * 用于在 SPACE_SHARED 模式下，向目标空间中当前具有指定角色的有效成员开放明确的资源动作。
     * 例如向 MEMBER 角色开放模型使用；角色按实际值匹配，不因 ADMIN 或 OWNER 的治理级别更高而自动匹配。
     * 成员角色变化后按新角色重新判断，具体允许动作由授权命令另行指定。
     *
     * @param scope 接收共享授权的目标空间范围，包含租户 ID 和空间 ID；仍需服务端核验范围及分享政策。
     * @param role  需要匹配的空间角色，不是租户角色，也不代表角色本身已获得资源访问权。
     */
    record SpaceRole(Scope scope, WorkspaceRoleEnum role) implements GrantRecipient {
        public SpaceRole {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(role);
        }
    }

    /**
     * 整个空间的共享授权目标
     * <p>
     * 用于在 SPACE_SHARED 模式下，向目标空间的所有当前有效成员开放明确的资源动作，不限定成员角色。
     * 新加入的有效成员也参与匹配，退出或被移除的成员失去匹配资格；具体允许动作由授权命令另行指定。
     *
     * @param scope 接收共享授权的目标空间范围，包含租户 ID 和空间 ID；不包含同租户内的其他空间。
     */
    record Space(Scope scope) implements GrantRecipient {
        public Space {
            Objects.requireNonNull(scope);
        }
    }
}
