package com.arte.app.service.platform;

import com.arte.app.platform.persistence.mapper.PlatformWorkspaceMemberMapper;
import com.arte.app.pojo.platform.PlatformWorkspaceMemberDto;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.PageResult;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.WorkspaceMembership;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.WorkspaceMembershipStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 空间成员存储服务，保留完整范围和成员代次
 * <p>
 * 业务侧通过 core SPI 调用，数据库写入参与原物理库事务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Service("workspaceMembershipStoreService")
@ConditionalOnProperty(prefix = "arte.platform.persistence", name = "enabled", havingValue = "true")
public class WorkspaceMembershipStoreServiceImpl extends PlatformStoreSupport<PlatformWorkspaceMemberMapper, PlatformWorkspaceMemberDto> implements WorkspaceMembershipStore {

    public WorkspaceMembershipStoreServiceImpl(
            @Qualifier("platformWorkspaceMemberMapper") PlatformWorkspaceMemberMapper mapper,
            @Qualifier("platformTransactionRunner") PublicTransactionRunner tx) {
        super(mapper, tx);
    }

    @Override
    public Optional<WorkspaceMembership> find(Scope scope, String subject) {
        return one(() -> baseMapper.selectMember(scope, subject, false), PlatformRecords::workspaceMember);
    }

    @Override
    public Optional<WorkspaceMembership> findById(Scope scope, String id) {
        return one(() -> baseMapper.selectScopedId(scope, id), PlatformRecords::workspaceMember);
    }

    @Override
    public Optional<WorkspaceMembership> lock(Scope scope, String subject) {
        requireTenant(scope.tenantId());
        requireSubject(subject);
        return one(() -> baseMapper.selectMember(scope, subject, true), PlatformRecords::workspaceMember);
    }

    @Override
    public PageResult<WorkspaceMembership> list(Scope scope, PageQuery query) {
        long total = baseMapper.countMembers(scope);
        var rows = baseMapper.pageMembers(scope, query.size(), Math.multiplyExact(query.current() - 1, query.size())).stream()
                .map(PlatformRecords::workspaceMember).toList();
        return new PageResult<>(query.current(), query.size(), total, rows);
    }

    @Override
    public boolean insert(WorkspaceMembership value) {
        requireTenant(value.scope().tenantId());
        requireSubject(value.subjectId());
        return insertRecord(() -> baseMapper.insertFromWorkspace(PlatformRecords.workspaceMember(value)));
    }

    @Override
    public boolean replace(WorkspaceMembership value, long expected) {
        requireTenant(value.scope().tenantId());
        requireSubject(value.subjectId());
        next(value.version(), expected);
        return updateRecord(() -> baseMapper.replaceCas(PlatformRecords.workspaceMember(value), expected));
    }
}
