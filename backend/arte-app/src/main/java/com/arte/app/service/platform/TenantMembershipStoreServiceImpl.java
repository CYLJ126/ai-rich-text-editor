package com.arte.app.service.platform;

import com.arte.app.platform.persistence.mapper.PlatformTenantMemberMapper;
import com.arte.app.pojo.platform.PlatformTenantMemberDto;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.PageResult;
import com.arte.core.pojo.workspace.TenantMembership;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.TenantMembershipStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 租户成员存储服务，保留完整范围和成员代次
 * <p>
 * 业务侧通过 core SPI 调用，数据库写入参与原物理库事务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Service("tenantMembershipStoreService")
@ConditionalOnProperty(prefix = "arte.platform.persistence", name = "enabled", havingValue = "true")
public class TenantMembershipStoreServiceImpl extends PlatformStoreSupport<PlatformTenantMemberMapper, PlatformTenantMemberDto> implements TenantMembershipStore {

    public TenantMembershipStoreServiceImpl(
            @Qualifier("platformTenantMemberMapper") PlatformTenantMemberMapper mapper,
            @Qualifier("platformTransactionRunner") PublicTransactionRunner tx) {
        super(mapper, tx);
    }

    @Override
    public Optional<TenantMembership> find(String tenantId, String subject) {
        return one(() -> baseMapper.selectMember(tenantId, subject, false), PlatformRecords::tenantMember);
    }

    @Override
    public Optional<TenantMembership> findById(String tenantId, String id) {
        return one(() -> baseMapper.selectScopedId(tenantId, id), PlatformRecords::tenantMember);
    }

    @Override
    public Optional<TenantMembership> lock(String tenantId, String subject) {
        requireTenant(tenantId);
        requireSubject(subject);
        return one(() -> baseMapper.selectMember(tenantId, subject, true), PlatformRecords::tenantMember);
    }

    @Override
    public PageResult<TenantMembership> list(String tenantId, PageQuery query) {
        long total = baseMapper.countMembers(tenantId);
        var rows = baseMapper.pageMembers(tenantId, query.size(), Math.multiplyExact(query.current() - 1, query.size())).stream()
                .map(PlatformRecords::tenantMember).toList();
        return new PageResult<>(query.current(), query.size(), total, rows);
    }

    @Override
    public boolean insert(TenantMembership value) {
        requireTenant(value.tenantId());
        requireSubject(value.subjectId());
        return insertRecord(() -> baseMapper.insert(PlatformRecords.tenantMember(value)));
    }

    @Override
    public boolean replace(TenantMembership value, long expected) {
        requireTenant(value.tenantId());
        requireSubject(value.subjectId());
        next(value.version(), expected);
        return updateRecord(() -> baseMapper.replaceCas(PlatformRecords.tenantMember(value), expected));
    }
}
