package com.arte.app.service.platform;

import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.*;
import com.arte.app.pojo.platform.PlatformWorkspaceDto;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.*;
import com.arte.core.spi.workspace.WorkspaceAccessFactsStore;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;

/**
 * 七次有界集合查询的展示授权事实服务，不承担最终授权
 * <p>
 * 业务侧通过 core SPI 调用，数据库写入参与原物理库事务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Service("workspaceAccessFactsStoreService")
@ConditionalOnProperty(prefix = "arte.platform.persistence", name = "enabled", havingValue = "true")
public class WorkspaceAccessFactsStoreServiceImpl extends ServiceImpl<PlatformWorkspaceMapper, PlatformWorkspaceDto> implements WorkspaceAccessFactsStore {

    private final PlatformTenantMapper tenants;
    private final PlatformTenantMemberMapper tenantMembers;
    private final PlatformWorkspaceMemberMapper workspaceMembers;
    private final PlatformTenantPolicyMapper tenantPolicies;
    private final PlatformWorkspacePolicyMapper workspacePolicies;
    private final PlatformWorkspacePreferenceMapper preferences;
    private final PlatformJson json;

    public WorkspaceAccessFactsStoreServiceImpl(
            @Qualifier("platformWorkspaceMapper") PlatformWorkspaceMapper mapper,
            @Qualifier("platformTenantMapper") PlatformTenantMapper tenants,
            @Qualifier("platformTenantMemberMapper") PlatformTenantMemberMapper tenantMembers,
            @Qualifier("platformWorkspaceMemberMapper") PlatformWorkspaceMemberMapper workspaceMembers,
            @Qualifier("platformTenantPolicyMapper") PlatformTenantPolicyMapper tenantPolicies,
            @Qualifier("platformWorkspacePolicyMapper") PlatformWorkspacePolicyMapper workspacePolicies,
            @Qualifier("platformWorkspacePreferenceMapper") PlatformWorkspacePreferenceMapper preferences,
            @Qualifier("platformJson") PlatformJson json) {
        this.baseMapper = Objects.requireNonNull(mapper);
        this.tenants = Objects.requireNonNull(tenants);
        this.tenantMembers = Objects.requireNonNull(tenantMembers);
        this.workspaceMembers = Objects.requireNonNull(workspaceMembers);
        this.tenantPolicies = Objects.requireNonNull(tenantPolicies);
        this.workspacePolicies = Objects.requireNonNull(workspacePolicies);
        this.preferences = Objects.requireNonNull(preferences);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public WorkspaceAccessFacts read(String subject, Set<String> tenantIds, Set<Scope> scopes, boolean includeDefault) {
        com.arte.core.utils.platform.PlatformChecks.id(subject, "subjectId");
        if (tenantIds.size() > 100 || scopes.size() > 100 || scopes.stream().anyMatch(s -> !tenantIds.contains(s.tenantId())))
            throw new IllegalArgumentException("Invalid facts bounds");
        if (tenantIds.isEmpty())
            return new WorkspaceAccessFacts(Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        var tids = tenantIds.stream().sorted().toList();
        var tenantRows = tenants.selectByIds(tids).stream().map(PlatformRecords::tenant).toList();
        var members = tenantMembers.selectTenants(tids, subject).stream().map(PlatformRecords::tenantMember).toList();
        var policies = tenantPolicies.selectByIds(tids).stream().map(v -> PlatformRecords.tenantPolicy(v, json)).toList();
        Map<Scope, Workspace> ws = Map.of();
        Map<Scope, WorkspaceMembership> wm = Map.of();
        Map<Scope, WorkspacePolicy> wp = Map.of();
        if (!scopes.isEmpty()) {
            var ranges = scopes.stream().sorted(Comparator.comparing(Scope::tenantId).thenComparing(Scope::workspaceId)).toList();
            ws = index(baseMapper.selectScopes(ranges).stream().map(PlatformRecords::workspace).toList(), Workspace::scope);
            wm = index(workspaceMembers.selectScopes(ranges, subject).stream().map(PlatformRecords::workspaceMember).toList(), WorkspaceMembership::scope);
            wp = index(workspacePolicies.selectScopes(ranges).stream().map(v -> PlatformRecords.workspacePolicy(v, json)).toList(), WorkspacePolicy::scope);
        }
        Map<String, WorkspacePreference> defaults = new HashMap<>();
        if (includeDefault) {
            var bySubject = new HashMap<String, String>();
            for (var tenant : tenantRows)
                if (tenant.personalSubjectId() != null) bySubject.put(tenant.personalSubjectId(), tenant.id());
            if (!bySubject.isEmpty()) {
                for (var row : preferences.selectByIds(bySubject.keySet())) {
                    var value = PlatformRecords.preference(row, json);
                    defaults.put(bySubject.get(value.subjectId()), value);
                }
            }
        }
        return new WorkspaceAccessFacts(index(tenantRows, Tenant::id), index(members, TenantMembership::tenantId),
                index(policies, TenantPolicy::tenantId), ws, wm, wp, defaults);
    }

    private static <K, V> Map<K, V> index(List<V> rows, Function<V, K> key) {
        var values = new HashMap<K, V>();
        for (var value : rows)
            if (values.put(key.apply(value), value) != null) throw new IllegalStateException("Duplicate facts");
        return values;
    }
}
