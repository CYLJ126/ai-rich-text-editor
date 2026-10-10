package com.arte.core.pojo.workspace;

import com.arte.core.pojo.platform.Scope;

import java.util.Map;

/**
 * 一批授权所需的原始事实，不是允许结论；缺失记录不填充默认角色或默认政策。
 * 各映射仅包含请求范围，成员只包含本批主体；defaultPreferences 按个人租户 ID 索引。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 12:35 ✾
 */
public record WorkspaceAccessFacts(
        Map<String, Tenant> tenants,
        Map<String, TenantMembership> tenantMembers,
        Map<String, TenantPolicy> tenantPolicies,
        Map<Scope, Workspace> workspaces,
        Map<Scope, WorkspaceMembership> workspaceMembers,
        Map<Scope, WorkspacePolicy> workspacePolicies,
        Map<String, WorkspacePreference> defaultPreferences) {

    public WorkspaceAccessFacts {
        tenants = Map.copyOf(tenants);
        tenantMembers = Map.copyOf(tenantMembers);
        tenantPolicies = Map.copyOf(tenantPolicies);
        workspaces = Map.copyOf(workspaces);
        workspaceMembers = Map.copyOf(workspaceMembers);
        workspacePolicies = Map.copyOf(workspacePolicies);
        defaultPreferences = Map.copyOf(defaultPreferences);
    }
}
