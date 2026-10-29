package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 工作空间写命令，如创建空间，修改空间名等。
 * <p>
 * 无 actor、owner 或权限声明，当前主体作为公共 API 独立参数。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public final class WorkspaceCommands {

    private WorkspaceCommands() {
    }

    /**
     * 创建空间。当前主体成为初始负责人，创建资格仍须服务端检查。
     */
    public record CreateWorkspace(
            CommandMeta meta,
            String tenantId,
            String name,
            String description) {

        public CreateWorkspace {
            Objects.requireNonNull(meta, "meta");
            PlatformChecks.id(tenantId, "tenantId");
            name = PlatformChecks.name(name);
            description = PlatformChecks.description(description);
        }
    }

    /**
     * 改名不改变归属、负责人或模型默认。
     */
    public record RenameWorkspace(
            CommandMeta meta,
            Scope scope,
            long expectedVersion,
            String name,
            String description) {

        public RenameWorkspace {
            Objects.requireNonNull(meta, "meta");
            Objects.requireNonNull(scope, "scope");
            PlatformChecks.version(expectedVersion, "expectedVersion");
            name = PlatformChecks.name(name);
            description = PlatformChecks.description(description);
        }
    }

    /**
     * 为空时应用服务取 7 天期限，最长 30 天；不邀请治理角色。
     */
    public record InviteMember(
            CommandMeta meta,
            Scope scope,
            long expectedWorkspaceVersion,
            String targetSubjectId,
            WorkspaceRoleEnum role,
            Instant expiresAt) {

        public InviteMember {
            Objects.requireNonNull(meta, "meta");
            Objects.requireNonNull(scope, "scope");
            PlatformChecks.version(expectedWorkspaceVersion, "expectedWorkspaceVersion");
            PlatformChecks.id(targetSubjectId, "targetSubjectId");
            Objects.requireNonNull(role, "role");
            PlatformChecks.require(role == WorkspaceRoleEnum.MEMBER || role == WorkspaceRoleEnum.GUEST, "invitation role must be MEMBER or GUEST");
        }
    }

    /**
     * 目标主体从可信 actor 获取，已是成员不改变角色。
     */
    public record AcceptInvitation(
            CommandMeta meta,
            String invitationId,
            long expectedInvitationVersion) {

        public AcceptInvitation {
            Objects.requireNonNull(meta, "meta");
            PlatformChecks.id(invitationId, "invitationId");
            PlatformChecks.version(expectedInvitationVersion, "expectedInvitationVersion");
        }
    }

    /**
     * 撤销未消费邀请，仍须当前邀请治理资格。
     */
    public record RevokeInvitation(
            CommandMeta meta,
            Scope scope,
            String invitationId,
            long expectedInvitationVersion,
            long expectedWorkspaceVersion) {

        public RevokeInvitation {
            Objects.requireNonNull(meta, "meta");
            Objects.requireNonNull(scope, "scope");
            PlatformChecks.id(invitationId, "invitationId");
            PlatformChecks.version(expectedInvitationVersion, "expectedInvitationVersion");
            PlatformChecks.version(expectedWorkspaceVersion, "expectedWorkspaceVersion");
        }
    }

    /**
     * 普通改角色不能授予 OWNER；原 OWNER 降级由服务拒绝。
     */
    public record ChangeMemberRole(
            CommandMeta meta,
            Scope scope,
            String memberId,
            long expectedMemberVersion,
            long expectedWorkspaceVersion,
            WorkspaceRoleEnum role) {

        public ChangeMemberRole {
            Objects.requireNonNull(meta, "meta");
            Objects.requireNonNull(scope, "scope");
            PlatformChecks.id(memberId, "memberId");
            PlatformChecks.version(expectedMemberVersion, "expectedMemberVersion");
            PlatformChecks.version(expectedWorkspaceVersion, "expectedWorkspaceVersion");
            Objects.requireNonNull(role, "role");
            PlatformChecks.require(role != WorkspaceRoleEnum.OWNER, "OWNER requires transfer command");
        }
    }

    /**
     * 移除成员，增加授权版本，保留原记录。
     */
    public record RemoveMember(
            CommandMeta meta,
            Scope scope,
            String memberId,
            long expectedMemberVersion,
            long expectedWorkspaceVersion) {

        public RemoveMember {
            Objects.requireNonNull(meta, "meta");
            Objects.requireNonNull(scope, "scope");
            PlatformChecks.id(memberId, "memberId");
            PlatformChecks.version(expectedMemberVersion, "expectedMemberVersion");
            PlatformChecks.version(expectedWorkspaceVersion, "expectedWorkspaceVersion");
        }
    }

    /**
     * 退出对象固定为可信 actor，负责人须先交接。
     */
    public record LeaveWorkspace(
            CommandMeta meta,
            Scope scope,
            long expectedMemberVersion,
            long expectedWorkspaceVersion) {

        public LeaveWorkspace {
            Objects.requireNonNull(meta, "meta");
            Objects.requireNonNull(scope, "scope");
            PlatformChecks.version(expectedMemberVersion, "expectedMemberVersion");
            PlatformChecks.version(expectedWorkspaceVersion, "expectedWorkspaceVersion");
        }
    }

    /**
     * 同事务交接；不改变托管租户。
     */
    public record TransferOwner(
            CommandMeta meta,
            Scope scope,
            String targetMemberId,
            long expectedOwnerMemberVersion,
            long expectedTargetMemberVersion,
            long expectedWorkspaceVersion) {

        public TransferOwner {
            Objects.requireNonNull(meta, "meta");
            Objects.requireNonNull(scope, "scope");
            PlatformChecks.id(targetMemberId, "targetMemberId");
            PlatformChecks.version(expectedOwnerMemberVersion, "expectedOwnerMemberVersion");
            PlatformChecks.version(expectedTargetMemberVersion, "expectedTargetMemberVersion");
            PlatformChecks.version(expectedWorkspaceVersion, "expectedWorkspaceVersion");
        }
    }

    /**
     * archive／unarchive 通过不同 API 方法表达意图，不接受任意目标状态。
     */
    public record ChangeWorkspaceState(
            CommandMeta meta,
            Scope scope,
            long expectedWorkspaceVersion,
            String reason) {

        public ChangeWorkspaceState {
            Objects.requireNonNull(meta, "meta");
            Objects.requireNonNull(scope, "scope");
            PlatformChecks.version(expectedWorkspaceVersion, "expectedWorkspaceVersion");
            reason = PlatformChecks.description(reason);
        }
    }

    /**
     * 关闭受理固定 CLOSING；不能自报关闭完成或清理结果。
     */
    public record CloseWorkspace(
            CommandMeta meta,
            Scope scope,
            long expectedWorkspaceVersion,
            String reason) {

        public CloseWorkspace {
            Objects.requireNonNull(meta, "meta");
            Objects.requireNonNull(scope, "scope");
            PlatformChecks.version(expectedWorkspaceVersion, "expectedWorkspaceVersion");
            reason = PlatformChecks.description(reason);
        }
    }

    /**
     * 只修改本人启动、最近访问及收藏，不修改默认空间或当前标签上下文。
     */
    public record UpdatePreference(
            CommandMeta meta,
            long expectedPreferenceVersion,
            Scope startupScope,
            Scope lastScope,
            Set<Scope> favorites) {

        public UpdatePreference {
            Objects.requireNonNull(meta, "meta");
            PlatformChecks.version(expectedPreferenceVersion, "expectedPreferenceVersion");
            favorites = Set.copyOf(favorites);
        }
    }

    /**
     * 政策字段受租户上限约束，Schema／角色预设版本由服务器保留。
     */
    public record UpdateWorkspacePolicy(
            CommandMeta meta,
            Scope scope,
            long expectedWorkspaceVersion,
            long expectedPolicyVersion,
            PolicyRules rules) {

        public UpdateWorkspacePolicy {
            Objects.requireNonNull(meta, "meta");
            Objects.requireNonNull(scope, "scope");
            PlatformChecks.version(expectedWorkspaceVersion, "expectedWorkspaceVersion");
            PlatformChecks.version(expectedPolicyVersion, "expectedPolicyVersion");
            Objects.requireNonNull(rules, "rules");
        }
    }
}
