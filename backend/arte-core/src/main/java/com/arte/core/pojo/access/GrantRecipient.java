package com.arte.core.pojo.access;

import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 写命令的期望授权目标，不接受客户端自报成员代次。服务端验证后生成 GrantTarget。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public sealed interface GrantRecipient permits GrantRecipient.Subject, GrantRecipient.SpaceRole, GrantRecipient.Space {
    record Subject(String subjectId) implements GrantRecipient {
        public Subject {
            PlatformChecks.id(subjectId, "subjectId");
        }
    }

    record SpaceRole(Scope scope, WorkspaceRoleEnum role) implements GrantRecipient {
        public SpaceRole {
            Objects.requireNonNull(scope);
            Objects.requireNonNull(role);
        }
    }

    record Space(Scope scope) implements GrantRecipient {
        public Space {
            Objects.requireNonNull(scope);
        }
    }
}
