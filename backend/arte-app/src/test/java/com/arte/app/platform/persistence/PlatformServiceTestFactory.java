package com.arte.app.platform.persistence;

import com.arte.app.service.platform.*;
import com.arte.core.spi.platform.PublicTransactionRunner;

import javax.sql.DataSource;

/**
 * 集成测试用真实 Mapper 装配，不替代生产组件扫描。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 16:28 ✾
 */
final class PlatformServiceTestFactory {
    private final PlatformMybatisConfiguration config = new PlatformMybatisConfiguration();
    private final PlatformMybatisSession session;
    private final PublicTransactionRunner tx;
    private final PlatformJson json;
    private final WorkspaceCursorStore cursors;

    PlatformServiceTestFactory(DataSource source, PublicTransactionRunner tx, PlatformJson json, WorkspaceCursorStore cursors) {
        this.tx = tx;
        this.json = json;
        this.cursors = cursors;
        var properties = new PlatformPersistenceProperties(true, 1, 1, 5, 15, 0,
                null, null, null, null, null, false);
        this.session = config.platformMapperSession(source, properties);
    }

    TenantStoreServiceImpl tenants() {
        return new TenantStoreServiceImpl(config.platformTenantMapper(session), tx);
    }

    WorkspaceStoreServiceImpl workspaces() {
        return new WorkspaceStoreServiceImpl(config.platformWorkspaceMapper(session), tx, cursors);
    }

    TenantMembershipStoreServiceImpl tenantMembers() {
        return new TenantMembershipStoreServiceImpl(config.platformTenantMemberMapper(session), tx);
    }

    WorkspaceMembershipStoreServiceImpl workspaceMembers() {
        return new WorkspaceMembershipStoreServiceImpl(config.platformWorkspaceMemberMapper(session), tx);
    }

    PreferenceStoreServiceImpl preferences() {
        return new PreferenceStoreServiceImpl(config.platformWorkspacePreferenceMapper(session), tx, json);
    }

    PolicyStoreServiceImpl policies() {
        return new PolicyStoreServiceImpl(config.platformTenantPolicyMapper(session), tx, config.platformWorkspacePolicyMapper(session), json);
    }

    CommandReceiptStoreServiceImpl receipts() {
        return new CommandReceiptStoreServiceImpl(config.platformCommandMapper(session), tx, json);
    }

    PublicOutboxStoreServiceImpl outbox() {
        return new PublicOutboxStoreServiceImpl(config.platformOutboxMapper(session), tx, json);
    }

    AuditSinkServiceImpl audit() {
        return new AuditSinkServiceImpl(config.platformAuditMapper(session), tx);
    }

    WorkspaceAccessFactsStoreServiceImpl facts() {
        return new WorkspaceAccessFactsStoreServiceImpl(config.platformWorkspaceMapper(session), config.platformTenantMapper(session),
                config.platformTenantMemberMapper(session), config.platformWorkspaceMemberMapper(session), config.platformTenantPolicyMapper(session),
                config.platformWorkspacePolicyMapper(session), config.platformWorkspacePreferenceMapper(session), json);
    }
}
