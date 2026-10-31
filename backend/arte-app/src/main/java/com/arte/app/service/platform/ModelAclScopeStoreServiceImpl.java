package com.arte.app.service.platform;

import com.arte.ainew.pojo.model.ModelAclScope;
import com.arte.ainew.spi.model.ModelAclScopeStore;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformModelAclScopeMapper;
import com.arte.app.pojo.platform.PlatformModelAclScopeDto;
import com.arte.core.application.platform.DefaultCommandExecutor;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.spi.platform.PublicTransactionRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;

/**
 * 缺表、故障及坏数据直接失败；只有精确范围没有记录才返回未迁移。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 12:00 ✾
 */
@Service
@ConditionalOnProperty(prefix = "arte.platform.persistence", name = "enabled", havingValue = "true")
public class ModelAclScopeStoreServiceImpl extends PlatformStoreSupport<PlatformModelAclScopeMapper, PlatformModelAclScopeDto> implements ModelAclScopeStore {

    private final PlatformJson json;

    public ModelAclScopeStoreServiceImpl(@Qualifier("platformModelAclScopeMapper") PlatformModelAclScopeMapper mapper,
                                         @Qualifier("platformTransactionRunner") PublicTransactionRunner tx, PlatformJson json) {
        super(mapper, tx);
        this.json = json;
    }

    @Override
    public Optional<ModelAclScope> find(Scope scope) {
        return Optional.ofNullable(baseMapper.scope(key(scope), false)).map(row -> decode(row, scope));
    }

    @Override
    public Optional<ModelAclScope> findCurrent(Scope scope) {
        gate(scope);
        return Optional.ofNullable(baseMapper.scope(key(scope), true)).map(row -> decode(row, scope));
    }

    @Override
    public boolean insert(ModelAclScope value) {
        gate(value.scope());
        if (value.version() != 1) {
            throw new IllegalArgumentException("Initial migration version must be one");
        }
        return insertRecord(() -> baseMapper.insertScope(encode(value)));
    }

    @Override
    public boolean replace(ModelAclScope value, long expected) {
        gate(value.scope());
        next(value.version(), expected);
        ModelAclScope old = findCurrent(value.scope()).orElseThrow();
        if (old.configurationSource() != value.configurationSource()) {
            throw new IllegalArgumentException("Configuration source migration requires a separate protocol");
        }
        return updateRecord(() -> baseMapper.replaceScope(encode(value), expected));
    }

    private static String key(Scope scope) {
        return DefaultCommandExecutor.digest(List.of(scope.tenantId(), scope.workspaceId()));
    }

    private void gate(Scope scope) {
        tx.requireWritable();
        requireScope(scope);
    }

    private ModelAclScope decode(PlatformModelAclScopeDto row, Scope scope) {
        ModelAclScope value = json.read(row.getPayload(), ModelAclScope.class);
        if (!value.scope().equals(scope) || !row.getScopeKey().equals(key(scope)) || !row.getTenantId().equals(scope.tenantId())
                || !row.getWorkspaceId().equals(scope.workspaceId()) || row.getVersion() != value.version()) {
            throw new IllegalStateException("Corrupt model ACL scope");
        }
        return value;
    }

    private PlatformModelAclScopeDto encode(ModelAclScope value) {
        PlatformModelAclScopeDto row = new PlatformModelAclScopeDto();
        row.setScopeKey(key(value.scope()));
        row.setTenantId(value.scope().tenantId());
        row.setWorkspaceId(value.scope().workspaceId());
        row.setVersion(value.version());
        row.setPayload(json.write(value));
        return row;
    }
}
