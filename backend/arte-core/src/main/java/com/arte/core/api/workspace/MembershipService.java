package com.arte.core.api.workspace;

import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import com.arte.core.pojo.workspace.WorkspaceViews;

/**
 * 空间成员及邀请公共 API，管理 Workspace 成员
 * <p>
 * OWNER 不经普通角色变更授予或移除，交接与范围校验原子提交。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface MembershipService {

    /**
     * 分页查询指定空间中当前操作者有资格查看的成员。
     *
     * @param actor 经可信认证适配确认的当前操作者
     * @param scope 当前选择的租户与工作空间范围，仍需服务端验证
     * @param query 分页查询条件
     * @return 可见空间成员的分页结果
     */
    PageResult<WorkspaceViews.MemberView> listMembers(TrustedActor actor, Scope scope, PageQuery query);

    /**
     * 分页查询发给当前操作者的空间邀请。
     *
     * @param actor 经可信认证适配确认的当前操作者
     * @param query 分页查询条件
     * @return 当前操作者收到的邀请分页结果
     */
    PageResult<WorkspaceViews.InvitationView> listInvitations(TrustedActor actor, PageQuery query);

    /**
     * 邀请已有账户以 MEMBER 或 GUEST 角色加入空间。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 邀请命令，包含空间、目标主体、角色、期限、预期版本及命令元数据
     * @return 邀请操作回执，包含邀请信息
     */
    CommandReceipt<WorkspaceViews.InvitationView> invite(TrustedActor actor, WorkspaceCommands.InviteMember command);

    /**
     * 撤销尚未消费的空间邀请。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 撤销邀请命令，包含空间、邀请 ID、预期版本及命令元数据
     * @return 撤销操作回执，包含更新后的邀请信息
     */
    CommandReceipt<WorkspaceViews.InvitationView> revokeInvitation(TrustedActor actor, WorkspaceCommands.RevokeInvitation command);

    /**
     * 由当前操作者接受发给本人的邀请，已是成员时不改变角色。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 接受邀请命令，包含邀请 ID、预期邀请版本及命令元数据
     * @return 接受邀请回执，包含加入结果及实际空间范围
     */
    CommandReceipt<WorkspaceViews.JoinResult> accept(TrustedActor actor, WorkspaceCommands.AcceptInvitation command);

    /**
     * 更新空间成员角色，OWNER 变更须使用负责人交接操作。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 角色变更命令，包含空间、目标成员、角色、预期版本及命令元数据
     * @return 角色更新回执，包含更新后的成员信息
     */
    CommandReceipt<WorkspaceViews.MemberView> changeRole(TrustedActor actor, WorkspaceCommands.ChangeMemberRole command);

    /**
     * 移除空间成员并使其当前成员资格失效。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 成员移除命令，包含空间、目标成员、预期版本及命令元数据
     * @return 成员移除回执，业务结果为空
     */
    CommandReceipt<Void> removeMember(TrustedActor actor, WorkspaceCommands.RemoveMember command);

    /**
     * 由当前操作者退出空间，负责人须先完成交接。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 退出命令，包含空间、预期成员与空间版本及命令元数据
     * @return 退出空间回执，业务结果为空
     */
    CommandReceipt<Void> leave(TrustedActor actor, WorkspaceCommands.LeaveWorkspace command);

    /**
     * 在同一事务内交接空间负责人，不改变空间归属。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 负责人交接命令，包含空间、目标成员、双方成员与空间预期版本及命令元数据
     * @return 交接操作回执，包含更新后的空间信息
     */
    CommandReceipt<WorkspaceViews.WorkspaceView> transferOwner(TrustedActor actor, WorkspaceCommands.TransferOwner command);
}
