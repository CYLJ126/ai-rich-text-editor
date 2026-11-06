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
     *
     * @param id 租户 ID
     * @param name 展示名称
     * @param type 租户类型
     * @param state 租户当前生命周期状态
     * @param currentRole 当前主体在该范围内的角色
     * @param version 租户记录版本
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
     *
     * @param scope 目标空间范围，包含租户 ID 和空间 ID
     * @param name 展示名称
     * @param state 空间当前生命周期状态
     * @param currentRole 当前主体在该范围内的角色
     * @param defaultWorkspace 是否为当前主体的默认空间
     * @param version 空间记录版本
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
     *
     * @param scope 目标空间范围，包含租户 ID 和空间 ID
     * @param name 展示名称
     * @param description 空间描述
     * @param state 空间当前生命周期状态
     * @param currentRole 当前主体在该范围内的角色
     * @param version 空间记录版本
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
     *
     * @param memberId 空间成员关系记录 ID
     * @param subjectId 该成员对应的主体 ID
     * @param role 该成员的空间角色
     * @param state 成员关系当前状态
     * @param version 空间成员关系记录版本
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
     *
     * @param memberId 租户成员关系记录 ID
     * @param subjectId 该成员对应的主体 ID
     * @param role 该成员的租户角色
     * @param state 成员关系当前状态
     * @param version 租户成员关系记录版本
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
     * 向受邀主体或获授权治理者展示空间邀请。
     *
     * @param invitationId 邀请记录 ID
     * @param scope 目标空间范围，包含租户 ID 和空间 ID
     * @param targetSubjectId 受邀账号的主体 ID
     * @param role 接受邀请后授予的空间角色
     * @param state 对外展示的邀请状态，包含按到期时间判定的过期状态
     * @param expiresAt 邀请到期时间
     * @param version 空间邀请记录版本
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
     * 返回接受空间邀请后的加入结果和当前成员信息。
     *
     * @param outcome 加入结果，区分本次加入与已有成员关系
     * @param scope 实际加入的空间范围
     * @param member 加入结果对应的当前空间成员信息
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
     * 展示组织租户的成员邀请及其当前状态。
     *
     * @param invitationId    邀请记录 ID
     * @param tenantId        邀请所属的组织租户 ID
     * @param targetSubjectId 受邀账号的主体 ID
     * @param state           对外展示的邀请状态，包含按到期时间判定的过期状态
     * @param expiresAt       邀请到期时间
     * @param version         租户邀请记录版本
     */
    public record TenantInvitationView(String invitationId, String tenantId, String targetSubjectId,
                                       InvitationStateEnum state, Instant expiresAt, long version) {
    }

    /**
     * 返回接受组织租户邀请后的加入结果和当前成员信息。
     *
     * @param outcome 加入结果，区分本次加入与已有成员关系
     * @param tenantId 实际加入的组织租户 ID
     * @param member 加入结果对应的当前租户成员信息
     */
    public record TenantJoinResult(JoinOutcomeEnum outcome, String tenantId, TenantMemberView member) {
    }

    /**
     * 展示当前主体在租户或空间范围内可用的成员操作。
     *
     * @param available 当前范围内的成员能力信息是否可用
     * @param manage 当前主体是否可以管理成员
     * @param invite 当前主体是否可以发出成员邀请
     * @param assignAdmin 当前主体是否可以授予管理员角色
     * @param transferOwner 当前主体是否可以发起负责人交接
     * @param leave 当前主体是否可以主动退出该空间
     * @param memberId 可执行管理或退出操作时返回的当前主体成员关系 ID，否则为 {@code null}
     * @param memberVersion 对应的当前主体成员关系版本，未返回成员关系 ID 时为 {@code null}
     */
    public record MembershipCapabilities(boolean available, boolean manage, boolean invite, boolean assignAdmin,
                                         boolean transferOwner, boolean leave, String memberId, Long memberVersion) {
    }

    /**
     * 本人偏好 View，引用仍需重新验证。
     *
     * @param defaultScope 当前主体的默认空间范围
     * @param startupScope 用户设置的启动空间范围，未设置时为 {@code null}，已设置但当前无法访问时返回默认空间范围
     * @param lastScope 最近访问的空间范围，未记录或当前不可用时为 {@code null}
     * @param favorites 当前可见的收藏空间范围集合
     * @param version 个人偏好记录版本
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
     *
     * @param tenant 个人租户摘要
     * @param workspace 个人租户的默认空间详情
     * @param preference 当前主体的空间偏好
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
