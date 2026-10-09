package com.arte.core.application.workspace;

import com.arte.core.pojo.workspace.Tenant;
import com.arte.core.pojo.workspace.Workspace;
import com.arte.core.pojo.workspace.WorkspacePreference;
import com.arte.core.pojo.workspace.WorkspaceViews;

/**
 * 只输出公共 View，完整政策与内部授权链不进入结果。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 22:35 ✾
 */
final class WorkspaceViewMapper {

    private WorkspaceViewMapper() {
    }

    static WorkspaceViews.WorkspaceView workspace(WorkspaceAccessSupport.WorkspaceSnapshot workspaceSnapshot) {
        Workspace workspace = workspaceSnapshot.workspace();
        return new WorkspaceViews.WorkspaceView(workspace.scope(), workspace.name(), workspace.description(),
                workspace.state(), workspaceSnapshot.member().role(), workspace.version());
    }

    static WorkspaceViews.TenantView tenant(WorkspaceAccessSupport.TenantSnapshot snapshot) {
        Tenant tenant = snapshot.tenant();
        return new WorkspaceViews.TenantView(tenant.id(), tenant.name(), tenant.type(), tenant.state(),
                snapshot.member().role(), tenant.version());
    }

    static WorkspaceViews.PreferenceView preference(WorkspacePreference preference) {
        return new WorkspaceViews.PreferenceView(preference.defaultScope(), preference.startupScope(),
                preference.lastScope(), preference.favorites(), preference.version());
    }
}
