package com.arte.core.api.workspace;

import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.PageResult;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.TenantCommands;
import com.arte.core.pojo.workspace.WorkspaceViews;

/**
 * 租户治理 API
 * <p>
 * 组织创建受 provisioning policy 限制；tenant 管理角色不获得私有正文读取。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public interface TenantService extends TenantGovernanceService {

    /**
     * 查询当前操作者有资格查看的租户信息。
     *
     * @param actor    经可信认证适配确认的当前操作者
     * @param tenantId 目标租户 ID
     * @return 当前可见的租户摘要及角色信息
     */
    WorkspaceViews.TenantView getTenant(TrustedActor actor, String tenantId);

    /**
     * 分页查询当前操作者有资格查看的租户成员。
     *
     * @param actor    经可信认证适配确认的当前操作者
     * @param tenantId 目标租户 ID
     * @param query    分页查询条件
     * @return 可见租户成员的分页结果
     */
    PageResult<WorkspaceViews.TenantMemberView> listMembers(TrustedActor actor, String tenantId, PageQuery query);

    /**
     * 更新租户成员角色，OWNER 变更须使用负责人交接操作。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 租户成员角色变更命令，包含租户、目标成员、角色、预期版本及命令元数据
     * @return 角色更新回执，包含更新后的租户成员信息
     */
    CommandReceipt<WorkspaceViews.TenantMemberView> changeMemberRole(TrustedActor actor, TenantCommands.ChangeTenantMemberRole command);

    /**
     * 撤销租户成员资格，并使其依赖的空间资格失效。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 租户成员移除命令，包含租户、目标成员、预期版本及命令元数据
     * @return 租户成员移除回执，业务结果为空
     */
    CommandReceipt<Void> removeMember(TrustedActor actor, TenantCommands.RemoveTenantMember command);

    /**
     * 在同一事务内交接租户负责人。
     *
     * @param actor   经可信认证适配确认的当前操作者
     * @param command 租户负责人交接命令，包含租户、目标成员、双方成员与租户预期版本及命令元数据
     * @return 交接操作回执，包含更新后的租户信息
     */
    CommandReceipt<WorkspaceViews.TenantView> transferOwner(TrustedActor actor, TenantCommands.TransferTenantOwner command);

    PageResult<WorkspaceViews.TenantInvitationView> listTenantInvitations(TrustedActor actor, String tenantId, PageQuery query);

    PageResult<WorkspaceViews.TenantInvitationView> listMyTenantInvitations(TrustedActor actor, PageQuery query);

    CommandReceipt<WorkspaceViews.TenantInvitationView> inviteTenantMember(TrustedActor actor, TenantCommands.InviteTenantMember command);

    CommandReceipt<WorkspaceViews.TenantInvitationView> revokeTenantInvitation(TrustedActor actor, TenantCommands.RevokeTenantInvitation command);

    CommandReceipt<WorkspaceViews.TenantJoinResult> acceptTenantInvitation(TrustedActor actor, TenantCommands.AcceptTenantInvitation command);
}
