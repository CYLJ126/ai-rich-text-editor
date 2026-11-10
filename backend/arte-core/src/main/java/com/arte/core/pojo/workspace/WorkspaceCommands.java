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
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param tenantId 目标租户 ID
     * @param name 新空间的展示名称
     * @param description 新空间的描述
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
     * 更新空间的名称和描述。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param scope 目标空间范围，包含租户 ID 和空间 ID
     * @param expectedVersion 执行前须匹配的空间记录版本
     * @param name 更新后的空间展示名称
     * @param description 更新后的空间描述
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
     * 向指定主体发出空间成员邀请。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param scope 目标空间范围，包含租户 ID 和空间 ID
     * @param expectedWorkspaceVersion 执行前须匹配的空间记录版本
     * @param targetSubjectId 受邀账号的主体 ID
     * @param role 接受邀请后授予的空间角色，限 {@code MEMBER} 或 {@code GUEST}
     * @param expiresAt 请求的邀请到期时间，为 {@code null} 时使用 7 天默认期限，最长 30 天
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
     * 由当前可信主体接受空间邀请。已是成员时保留当前角色。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param invitationId 邀请记录 ID
     * @param expectedInvitationVersion 执行前须匹配的邀请记录版本
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
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param scope 目标空间范围，包含租户 ID 和空间 ID
     * @param invitationId 邀请记录 ID
     * @param expectedInvitationVersion 执行前须匹配的邀请记录版本
     * @param expectedWorkspaceVersion 执行前须匹配的空间记录版本
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
     * 变更空间成员的角色。负责人角色变更通过专用交接命令完成。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param scope 目标空间范围，包含租户 ID 和空间 ID
     * @param memberId 目标成员关系记录的 ID
     * @param expectedMemberVersion 执行前须匹配的成员关系记录版本
     * @param expectedWorkspaceVersion 执行前须匹配的空间记录版本
     * @param role 目标成员的新空间角色
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
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param scope 目标空间范围，包含租户 ID 和空间 ID
     * @param memberId 目标成员关系记录的 ID
     * @param expectedMemberVersion 执行前须匹配的成员关系记录版本
     * @param expectedWorkspaceVersion 执行前须匹配的空间记录版本
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
     * 由当前主体退出空间。负责人须先交接。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param scope 目标空间范围，包含租户 ID 和空间 ID
     * @param expectedMemberVersion 执行前须匹配的当前主体成员关系记录版本
     * @param expectedWorkspaceVersion 执行前须匹配的空间记录版本
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
     * 在同一事务中将空间负责人角色交接给指定成员。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param scope 目标空间范围，包含租户 ID 和空间 ID
     * @param targetMemberId 接任负责人的成员关系记录 ID
     * @param expectedOwnerMemberVersion 执行前须匹配的现任负责人关系记录版本
     * @param expectedTargetMemberVersion 执行前须匹配的接任者关系记录版本
     * @param expectedWorkspaceVersion 执行前须匹配的空间记录版本
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
     * 提交空间归档或取消归档命令，具体动作由调用的 API 方法确定。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param scope 目标空间范围，包含租户 ID 和空间 ID
     * @param expectedWorkspaceVersion 执行前须匹配的空间记录版本
     * @param reason 归档或取消归档的原因
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
     * 请求关闭空间，受理后空间进入 {@code CLOSING}。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param scope 目标空间范围，包含租户 ID 和空间 ID
     * @param expectedWorkspaceVersion 执行前须匹配的空间记录版本
     * @param reason 请求关闭空间的原因
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
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param expectedPreferenceVersion 执行前须匹配的个人偏好记录版本
     * @param startupScope 新的启动空间，为 {@code null} 时清除该偏好
     * @param lastScope 新的最近访问空间，为 {@code null} 时清除该偏好
     * @param favorites 更新后的收藏空间集合
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
     * 更新空间政策，规则受当前租户政策上限约束，沿用服务端记录的结构与角色预设版本。
     *
     * @param meta 命令元数据，包含幂等操作键和链路跟踪 ID
     * @param scope 目标空间范围，包含租户 ID 和空间 ID
     * @param expectedWorkspaceVersion 执行前须匹配的空间记录版本
     * @param expectedPolicyVersion 执行前须匹配的空间政策版本
     * @param rules 更新后的空间政策规则
     * @param expectedTenantPolicyVersion 可选的预期租户政策版本，提供时须匹配当前租户政策
     */
    public record UpdateWorkspacePolicy(
            CommandMeta meta,
            Scope scope,
            long expectedWorkspaceVersion,
            long expectedPolicyVersion,
            PolicyRules rules,
            Long expectedTenantPolicyVersion) {

        public UpdateWorkspacePolicy(CommandMeta meta, Scope scope, long expectedWorkspaceVersion,
                                     long expectedPolicyVersion, PolicyRules rules) {
            this(meta, scope, expectedWorkspaceVersion, expectedPolicyVersion, rules, null);
        }

        public UpdateWorkspacePolicy {
            Objects.requireNonNull(meta, "meta");
            Objects.requireNonNull(scope, "scope");
            PlatformChecks.version(expectedWorkspaceVersion, "expectedWorkspaceVersion");
            PlatformChecks.version(expectedPolicyVersion, "expectedPolicyVersion");
            Objects.requireNonNull(rules, "rules");
            if (expectedTenantPolicyVersion != null) {
                PlatformChecks.version(expectedTenantPolicyVersion, "expectedTenantPolicyVersion");
            }
        }
    }

    /**
     * 由受邀账号拒绝尚未到期的邀请；不建立或修改成员资格。
     *
     * @param meta                      命令元数据及原操作键
     * @param invitationId              本人收到的邀请 ID
     * @param expectedInvitationVersion 预期邀请版本
     */
    public record DeclineInvitation(CommandMeta meta, String invitationId, long expectedInvitationVersion) {
        public DeclineInvitation {
            Objects.requireNonNull(meta);
            PlatformChecks.id(invitationId, "invitationId");
            PlatformChecks.version(expectedInvitationVersion, "expectedInvitationVersion");
        }
    }

}
