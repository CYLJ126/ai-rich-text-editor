package com.arte.app.service.platform;

import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformTenantPolicyMapper;
import com.arte.app.platform.persistence.mapper.PlatformWorkspacePolicyMapper;
import com.arte.app.pojo.platform.PlatformTenantPolicyDto;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.TenantPolicy;
import com.arte.core.pojo.workspace.WorkspacePolicy;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.PolicyStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Objects;
import java.util.Optional;

/**
 * 两级策略存储服务，空间策略使用联合主键
 * <p>
 * 业务侧通过 core SPI 调用，数据库写入参与原物理库事务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Service("policyStoreService")
@ConditionalOnProperty(prefix = "arte.platform.persistence", name = "enabled", havingValue = "true")
public class PolicyStoreServiceImpl extends PlatformStoreSupport<PlatformTenantPolicyMapper, PlatformTenantPolicyDto> implements PolicyStore {

    private final PlatformWorkspacePolicyMapper workspacePolicies;
    private final PlatformJson json;

    public PolicyStoreServiceImpl(
            @Qualifier("platformTenantPolicyMapper") PlatformTenantPolicyMapper mapper,
            @Qualifier("platformTransactionRunner") PublicTransactionRunner tx,
            @Qualifier("platformWorkspacePolicyMapper") PlatformWorkspacePolicyMapper workspacePolicies,
            @Qualifier("platformJson") PlatformJson json) {
        super(mapper, tx);
        this.workspacePolicies = Objects.requireNonNull(workspacePolicies);
        this.json = Objects.requireNonNull(json);
    }

    @Override
    public Optional<TenantPolicy> findTenantPolicy(String tenant) {
        return one(() -> baseMapper.selectById(tenant), row -> PlatformRecords.tenantPolicy(row, json));
    }

    @Override
    public Optional<WorkspacePolicy> findWorkspacePolicy(Scope scope) {
        return Optional.ofNullable(workspacePolicies.selectByScope(scope.tenantId(), scope.workspaceId()))
                .map(row -> PlatformRecords.workspacePolicy(row, json));
    }

    @Override
    public Optional<TenantPolicy> findCurrentTenantPolicy(String tenant) {
        requireTenant(tenant);
        return one(() -> baseMapper.selectCurrent(tenant), row -> PlatformRecords.tenantPolicy(row, json));
    }

    @Override
    public Optional<WorkspacePolicy> findCurrentWorkspacePolicy(Scope scope) {
        requireScope(scope);
        return Optional.ofNullable(workspacePolicies.selectCurrent(scope)).map(row -> PlatformRecords.workspacePolicy(row, json));
    }

    @Override
    public boolean insertTenantPolicy(TenantPolicy value) {
        requireTenant(value.tenantId());
        return insertRecord(() -> baseMapper.insert(PlatformRecords.tenantPolicy(value, json)));
    }

    @Override
    public boolean insertWorkspacePolicy(WorkspacePolicy value) {
        requireScope(value.scope());
        return insertRecord(() -> workspacePolicies.insertPolicy(PlatformRecords.workspacePolicy(value, json)));
    }

    @Override
    public boolean replaceTenantPolicy(TenantPolicy value, long expected) {
        requireTenant(value.tenantId());
        next(value.version(), expected);
        return updateRecord(() -> baseMapper.replaceCas(PlatformRecords.tenantPolicy(value, json), expected));
    }

    @Override
    public boolean replaceWorkspacePolicy(WorkspacePolicy value, long expected) {
        requireScope(value.scope());
        next(value.version(), expected);
        return updateRecord(() -> workspacePolicies.replaceCas(PlatformRecords.workspacePolicy(value, json), expected));
    }
}
