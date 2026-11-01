package com.arte.app.service.platform;

import com.arte.ainew.pojo.model.ModelSharingPolicy;
import com.arte.ainew.spi.model.ModelSharingStore;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformModelResourceMapper;
import com.arte.app.platform.persistence.mapper.PlatformModelSharingMapper;
import com.arte.app.pojo.platform.PlatformModelResourceDto;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.spi.platform.PublicTransactionRunner;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 策略与索引一并校验、原子更新；继承事务代理要求本类可被继承。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/01 19:25 ✾
 */
public class ModelSharingStoreServiceImpl extends PlatformStoreSupport<PlatformModelResourceMapper, PlatformModelResourceDto> implements ModelSharingStore {

    private final PlatformModelSharingMapper mapper;
    private final PlatformJson json;

    public ModelSharingStoreServiceImpl(PlatformModelResourceMapper resources, PlatformModelSharingMapper mapper, PublicTransactionRunner tx, PlatformJson json) {
        super(resources, tx);
        this.mapper = mapper;
        this.json = json;
    }

    private String key(ResourceRef ref) {
        return DefaultCommandExecutor.digest(List.of(ref.tenantId(), ref.type().value(), ref.resourceId()));
    }

    @Override
    public Optional<ModelSharingPolicy> find(ResourceRef ref, boolean current) {
        tx.requireActive();
        if (current) {
            tx.requireWritable();
            requireTenant(ref.tenantId());
        }
        PlatformModelSharingMapper.Row row = mapper.find(key(ref), current);
        if (row == null) {
            return Optional.empty();
        }
        ModelSharingPolicy policy = json.read(row.payload(), ModelSharingPolicy.class);
        List<Scope> scopes = mapper.scopes(key(ref), current);
        if (!policy.resource().equals(ref) || !row.tenantId().equals(ref.tenantId()) || !row.resourceKey().equals(key(ref))
                || row.version() != policy.version() || scopes.stream().anyMatch(scope -> !scope.tenantId().equals(ref.tenantId()))
                || !scopes.stream().map(Scope::workspaceId).collect(Collectors.toSet()).equals(policy.workspaceIds())) {
            throw new IllegalStateException("Model sharing authority mismatch");
        }
        return Optional.of(policy);
    }

    private PlatformModelSharingMapper.Row row(ModelSharingPolicy p) {
        return new PlatformModelSharingMapper.Row(key(p.resource()), p.resource().tenantId(), p.version(), json.write(p));
    }

    @Override
    public boolean insert(ModelSharingPolicy policy) {
        tx.requireWritable();
        requireScope(policy.managementScope());
        if (policy.version() != 0) {
            throw new IllegalArgumentException("Initial sharing version must be zero");
        }
        if (!insertRecord(() -> mapper.insert(row(policy)))) {
            return false;
        }
        scopes(policy);
        validateTenant(policy.resource().tenantId(), true);
        return true;
    }

    @Override
    public boolean replace(ModelSharingPolicy policy, long expected) {
        tx.requireWritable();
        requireScope(policy.managementScope());
        next(policy.version(), expected);
        ModelSharingPolicy old = find(policy.resource(), true).orElseThrow();
        if (!old.managementScope().equals(policy.managementScope())) {
            throw new IllegalArgumentException("Management scope is immutable");
        }
        if (!updateRecord(() -> mapper.replace(row(policy), expected))) {
            return false;
        }
        write(() -> mapper.clearScopes(key(policy.resource())));
        scopes(policy);
        return true;
    }

    private void scopes(ModelSharingPolicy policy) {
        for (String workspace : policy.workspaceIds().stream().sorted().toList()) {
            if (write(() -> mapper.insertScope(key(policy.resource()), policy.resource().tenantId(), workspace)) != 1) {
                throw new IllegalStateException("Cannot store model use scope");
            }
        }
    }

    @Override
    public void validateTenant(String tenant, boolean current) {
        tx.requireActive();
        if (current) {
            tx.requireWritable();
            requireTenant(tenant);
        }
        List<PlatformModelSharingMapper.Row> rows = mapper.tenantRows(tenant, current);
        if (rows.size() > 256) {
            throw new IllegalStateException("Tenant model policy limit exceeded");
        }
        for (PlatformModelSharingMapper.Row row : rows) {
            ModelSharingPolicy policy = json.read(row.payload(), ModelSharingPolicy.class);
            if (!policy.resource().tenantId().equals(tenant)) {
                throw new IllegalStateException("Cross tenant model policy");
            }
            find(policy.resource(), current).orElseThrow();
        }
    }
}
