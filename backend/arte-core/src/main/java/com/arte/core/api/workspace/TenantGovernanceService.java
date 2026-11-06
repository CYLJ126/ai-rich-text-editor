package com.arte.core.api.workspace;

import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.workspace.TenantCommands;
import com.arte.core.pojo.workspace.WorkspaceViews;

/**
 * 租户基本信息和组织创建命令
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 17:11 ✾
 */
public interface TenantGovernanceService {

    CommandReceipt<WorkspaceViews.TenantView> createOrganization(TrustedActor actor, TenantCommands.CreateOrganization command);

    CommandReceipt<WorkspaceViews.TenantView> updateTenant(TrustedActor actor, TenantCommands.UpdateTenant command);
}
