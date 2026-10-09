package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.*;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 公共查询 View；不包含完整政策、凭据或内部权限链。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public final class WorkspaceViews {

    private WorkspaceViews() {
    }

    /**
     * 当前主体可见租户摘要。
     */
    public record TenantView(
            String id,
            String name,
            TenantTypeEnum type,
            TenantStateEnum state,
            TenantRoleEnum currentRole,
            long version) {
        public TenantView {
            PlatformChecks.id(id, "id");
            name = PlatformChecks.name(name);
            Objects.requireNonNull(type, "type");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(currentRole, "currentRole");
            PlatformChecks.version(version, "version");
        }
    }

    /**
     * 可访问空间列表项，默认标记源自本人偏好关联。
     */
    public record WorkspaceSummary(
            Scope scope,
            String name,
            WorkspaceStateEnum state,
            WorkspaceRoleEnum currentRole,
            boolean defaultWorkspace,
            long version) {
        public WorkspaceSummary {
            Objects.requireNonNull(scope, "scope");
            name = PlatformChecks.name(name);
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(currentRole, "currentRole");
            PlatformChecks.version(version, "version");
        }
    }

    /**
     * 当前空间公开元数据。
     */
    public record WorkspaceView(
            Scope scope,
            String name,
            String description,
            WorkspaceStateEnum state,
            WorkspaceRoleEnum currentRole,
            long version) {
        public WorkspaceView {
            Objects.requireNonNull(scope, "scope");
            name = PlatformChecks.name(name);
            description = PlatformChecks.description(description);
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(currentRole, "currentRole");
            PlatformChecks.version(version, "version");
        }
    }

    /**
     * 获授权的空间成员信息；不返回登录凭据。
     */
    public record MemberView(
            String memberId,
            String subjectId,
            WorkspaceRoleEnum role,
            MembershipStateEnum state,
            long version) {
        public MemberView {
            PlatformChecks.id(memberId, "memberId");
            PlatformChecks.id(subjectId, "subjectId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(state, "state");
            PlatformChecks.version(version, "version");
        }
    }

    /**
     * 租户治理成员信息，角色不与空间角色混用。
     */
    public record TenantMemberView(
            String memberId,
            String subjectId,
            TenantRoleEnum role,
            MembershipStateEnum state,
            long version) {
        public TenantMemberView {
            PlatformChecks.id(memberId, "memberId");
            PlatformChecks.id(subjectId, "subjectId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(state, "state");
            PlatformChecks.version(version, "version");
        }
    }

    /**
     * 邀请对象及治理者按资格查询。
     */
    public record InvitationView(
            String invitationId,
            Scope scope,
            String targetSubjectId,
            WorkspaceRoleEnum role,
            InvitationStateEnum state,
            Instant expiresAt,
            long version) {
        public InvitationView {
            PlatformChecks.id(invitationId, "invitationId");
            Objects.requireNonNull(scope, "scope");
            PlatformChecks.id(targetSubjectId, "targetSubjectId");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(expiresAt, "expiresAt");
            PlatformChecks.version(version, "version");
        }
    }

    /**
     * 返回真实加入范围和当前角色，不由客户端推断。
     */
    public record JoinResult(
            JoinOutcomeEnum outcome,
            Scope scope,
            MemberView member) {
        public JoinResult {
            Objects.requireNonNull(outcome, "outcome");
            Objects.requireNonNull(scope, "scope");
            Objects.requireNonNull(member, "member");
        }
    }

    /**
     * 本人偏好 View，引用仍需重新验证。
     */
    public record PreferenceView(
            Scope defaultScope,
            Scope startupScope,
            Scope lastScope,
            Set<Scope> favorites,
            long version) {
        public PreferenceView {
            Objects.requireNonNull(defaultScope, "defaultScope");
            favorites = Set.copyOf(favorites);
            PlatformChecks.version(version, "version");
        }
    }

    /**
     * 个人初始化完整结果。
     */
    public record PersonalWorkspaceView(
            TenantView tenant,
            WorkspaceView workspace,
            PreferenceView preference) {
        public PersonalWorkspaceView {
            Objects.requireNonNull(tenant, "tenant");
            Objects.requireNonNull(workspace, "workspace");
            Objects.requireNonNull(preference, "preference");
            PlatformChecks.require(tenant.id().equals(workspace.scope().tenantId()) && workspace.scope().equals(preference.defaultScope()), "personal associations mismatch");
        }
    }
}
