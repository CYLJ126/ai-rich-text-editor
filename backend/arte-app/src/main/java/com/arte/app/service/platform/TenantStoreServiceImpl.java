package com.arte.app.service.platform;

import com.arte.app.platform.persistence.mapper.PlatformTenantMapper;
import com.arte.app.pojo.platform.PlatformTenantDto;

import com.arte.core.pojo.workspace.Tenant;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.TenantStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 租户存储服务
 * <p>
 * 业务侧通过 core SPI 调用，数据库写入参与原物理库事务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Service("tenantStoreService")
@ConditionalOnProperty(prefix = "arte.platform.persistence", name = "enabled", havingValue = "true")
public class TenantStoreServiceImpl extends PlatformStoreSupport<PlatformTenantMapper, PlatformTenantDto> implements TenantStore {

    public TenantStoreServiceImpl(
            @Qualifier("platformTenantMapper") PlatformTenantMapper mapper,
            @Qualifier("platformTransactionRunner") PublicTransactionRunner tx) {
        super(mapper, tx);
    }

    @Override
    public Optional<Tenant> find(String id) {
        return one(() -> baseMapper.selectById(id), PlatformRecords::tenant);
    }

    @Override
    public Optional<Tenant> findPersonal(String subject) {
        return one(() -> baseMapper.selectPersonal(subject, false), PlatformRecords::tenant);
    }

    @Override
    public Optional<Tenant> findCurrentPersonal(String subject) {
        requireSubject(subject);
        return one(() -> baseMapper.selectPersonal(subject, true), PlatformRecords::tenant);
    }

    @Override
    public Optional<Tenant> lock(String id) {
        requireAnySubject();
        var found = one(() -> baseMapper.selectCurrent(id), PlatformRecords::tenant);
        found.ifPresent(v -> tenantHeld(id));
        return found;
    }

    @Override
    public boolean insert(Tenant value) {
        requireSubject(value.createdBy());
        boolean inserted = insertRecord(() -> baseMapper.insert(PlatformRecords.tenant(value)));
        if (inserted) tenantHeld(value.id());
        return inserted;
    }

    @Override
    public boolean replace(Tenant value, long expected) {
        requireTenant(value.id());
        next(value.version(), expected);
        return updateRecord(() -> baseMapper.replaceCas(PlatformRecords.tenant(value), expected));
    }
}
