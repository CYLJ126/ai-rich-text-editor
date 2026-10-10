package com.arte.app.service.platform;

import com.arte.app.platform.persistence.WorkspaceCursorStore;
import com.arte.app.platform.persistence.mapper.PlatformWorkspaceMapper;
import com.arte.app.pojo.platform.PlatformWorkspaceDto;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.Workspace;
import com.arte.core.pojo.workspace.WorkspaceCandidateKey;
import com.arte.core.pojo.workspace.WorkspaceCandidatePage;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.WorkspaceStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 空间存储与稳定游标目录服务
 * <p>
 * 业务侧通过 core SPI 调用，数据库写入参与原物理库事务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
@Service("workspaceStoreService")
@ConditionalOnProperty(prefix = "arte.platform.persistence", name = "enabled", havingValue = "true")
public class WorkspaceStoreServiceImpl extends PlatformStoreSupport<PlatformWorkspaceMapper, PlatformWorkspaceDto> implements WorkspaceStore {

    private final WorkspaceCursorStore cursors;

    public WorkspaceStoreServiceImpl(
            @Qualifier("platformWorkspaceMapper") PlatformWorkspaceMapper mapper,
            @Qualifier("platformTransactionRunner") PublicTransactionRunner tx,
            @Qualifier("workspaceCursorStore") WorkspaceCursorStore cursors) {
        super(mapper, tx);
        this.cursors = Objects.requireNonNull(cursors);
    }

    @Override
    public Optional<Workspace> find(Scope scope) {
        return one(() -> baseMapper.selectScoped(scope, false), PlatformRecords::workspace);
    }

    @Override
    public Optional<Workspace> lock(Scope scope) {
        requireTenant(scope.tenantId());
        var found = one(() -> baseMapper.selectScoped(scope, true), PlatformRecords::workspace);
        found.ifPresent(v -> scopeHeld(scope));
        return found;
    }

    @Override
    public boolean insert(Workspace value) {
        requireTenant(value.scope().tenantId());
        boolean inserted = insertRecord(() -> baseMapper.insert(PlatformRecords.workspace(value)));
        if (inserted) scopeHeld(value.scope());
        return inserted;
    }

    @Override
    public boolean replace(Workspace value, long expected) {
        requireScope(value.scope());
        next(value.version(), expected);
        return updateRecord(() -> baseMapper.replaceCas(PlatformRecords.workspace(value), expected));
    }

    @Override
    public WorkspaceCandidatePage scanMembershipCandidates(String subject, String cursor, int limit) {
        com.arte.core.utils.platform.PlatformChecks.id(subject, "subjectId");
        if (limit < 1 || limit > 100) throw new IllegalArgumentException("Invalid scan limit");
        PlatformWorkspaceDto key = null;
        if (cursor != null) {
            var saved = cursors.load(cursor);
            if (!saved.subjectId().equals(subject)) throw new IllegalArgumentException("Cursor subject mismatch");
            key = new PlatformWorkspaceDto();
            key.setTenantId(saved.key().scope().tenantId());
            key.setId(saved.key().scope().workspaceId());
            key.setCreatedSec(saved.key().createdAt().getEpochSecond());
            key.setCreatedNano(saved.key().createdAt().getNano());
        }
        var rows = baseMapper.scanCandidates(subject, subjectDigest(subject), key, limit + 1).stream()
                .map(PlatformRecords::workspace).toList();
        boolean more = rows.size() > limit;
        var records = List.copyOf(rows.subList(0, Math.min(limit, rows.size())));
        String next = more ? cursors.save(new WorkspaceCursorStore.Cursor(subject, WorkspaceCandidateKey.from(records.getLast()))) : null;
        return new WorkspaceCandidatePage(records, next);
    }
}
