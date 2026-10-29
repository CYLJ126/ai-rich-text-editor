package com.arte.core.pojo.workspace;

import com.arte.core.pojo.platform.Scope;

import java.util.Map;

/**
 * 一次批量授权判断所需的原始数据集合
 * <p>
 * 包含租户、空间、成员、政策和默认空间偏好，减少逐项查询。它提供判断依据，具体是否允许由授权服务决定。
 * 一批授权所需的原始事实，不是允许结论；缺失记录不填充默认角色或默认政策。
 * 各映射仅包含请求范围，成员只包含本批主体；defaultPreferences 按个人租户 ID 索引。
 * 用于空间目录等展示场景的批量访问判断，减少逐项读取的数据库往返；事实仅在本批复用，写入仍须重新做事务内最终授权。
 * 所有映射均防御性复制为不可变映射，映射及其键值不能为 null；缺失记录以映射中没有对应键表示。
 * </p>
 * 这是一个优化性改造，用于批量授权判断，减少逐项查询数据库的往返。
 * <ol>
 *     <li>收集本批需要检查的租户 ID、空间范围。</li>
 *     <li>按这些范围批量查询租户、空间、成员关系和政策，组装成 WorkspaceAccessFacts。</li>
 *     <li>逐条判断空间权限时，从这些 Map 中取数据。同一租户的事实可以被多个空间复用。</li>
 *     <li>Map 中缺少必要记录时，按缺失成员、资源或政策处理，不会自动补查数据库。</li>
 * </ol>
 *
 * @param tenants            按租户 ID 索引的租户记录，提供租户类型、状态及版本等事实。
 * @param tenantMembers      按租户 ID 索引的本批主体的租户成员记录，提供角色、成员状态、代次及版本等事实。
 * @param tenantPolicies     按租户 ID 索引的租户政策，用于校验租户侧的规则与权限上限。
 * @param workspaces         按完整 Scope 索引的空间记录，提供空间状态、展示信息及版本等事实。
 * @param workspaceMembers   按完整 Scope 索引的本批主体的空间成员记录，提供角色、成员状态、代次及版本等事实。
 * @param workspacePolicies  按完整 Scope 索引的空间政策，用于结合租户政策判断空间侧的访问规则。
 * @param defaultPreferences 按个人租户 ID 索引的其托管主体 personalSubjectId 对应的空间偏好，用于识别受保护的个人默认空间；未请求读取时为空映射。
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
