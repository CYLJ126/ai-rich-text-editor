package com.arte.app.platform.workspace;

import com.arte.ainew.application.workspace.JdbcWorkspaceRetentionInspector;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.app.platform.persistence.JdbcWorkspaceClosureStore;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.core.application.workspace.DefaultWorkspaceLifecycleService;
import com.arte.core.application.workspace.DefaultWorkspaceServices;
import com.arte.core.pojo.workspace.ClosureBlocker;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.*;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Scheduled;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.List;
import java.util.Map;

/**
 * 工作空间生命周期装配，不自动执行 DDL。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/10 18:40 ✾
 */
@Configuration(proxyBeanMethods = false)
public class WorkspaceLifecycleConfiguration {

    @Bean
    JdbcWorkspaceClosureStore workspaceClosureStore(@Qualifier("appDataSource") DataSource source,
                                                    @Qualifier("platformTransactionRunner") PublicTransactionRunner tx, PlatformJson json) {
        return new JdbcWorkspaceClosureStore(source, tx, json);
    }

    @Bean
    WorkspaceResourceRetentionInspector workspaceResourceRetentionInspector(@Qualifier("appDataSource") DataSource source, PlatformJson json) {
        return new WorkspaceResourceRetentionInspector(source, json);
    }

    @Bean
    WorkspaceClosureInspector workspaceAiRetentionInspector(@Qualifier("appDataSource") DataSource platform,
                                                            Environment environment, ListableBeanFactory beans) {
        String name = environment.getProperty("arte.ai-new.data-source-bean", "appDataSource");
        if (!beans.containsBean(name)) return new WorkspaceClosureInspector() {
            public String domain() {
                return "ai";
            }

            public Inspection inspect(com.arte.core.pojo.platform.Scope scope) {
                return new Inspection(List.of(new ClosureBlocker("ai", "retention", "AI_DATASOURCE_UNAVAILABLE", true)), Map.of());
            }
        };
        return new JdbcWorkspaceRetentionInspector(beans.getBean(name, DataSource.class), platform, new JacksonExecutionRecordCodec(),
                environment.getProperty("arte.ai-new.enabled", Boolean.class, false));
    }

    @Bean
    DefaultWorkspaceLifecycleService workspaceLifecycleService(IdentityProvider identity,
                                                               @Qualifier("platformTransactionRunner") PublicTransactionRunner tx, TenantStore tenants, WorkspaceStore spaces,
                                                               TenantMembershipStore tenantMembers, WorkspaceMembershipStore members, PolicyStore policies, PreferenceStore preferences,
                                                               WorkspaceAccessFactsStore facts, CommandReceiptStore receipts, PublicOutboxStore outbox, AuditSink audit,
                                                               PlatformIdGenerator ids, @Qualifier("platformWorkspaceClock") Clock clock, JdbcWorkspaceClosureStore store,
                                                               List<WorkspaceClosureInspector> inspectors, DefaultWorkspaceServices services, TenantInvitationStore invitations) {
        var lifecycle = new DefaultWorkspaceLifecycleService(identity, tx, tenants, spaces, tenantMembers, members, policies,
                preferences, facts, receipts, outbox, audit, ids, clock, store, inspectors, invitations);
        services.receiptQueries().registerLifecycle(lifecycle);
        return lifecycle;
    }

    @Bean
    ClosureScanner workspaceClosureScanner(DefaultWorkspaceLifecycleService lifecycle) {
        return new ClosureScanner(lifecycle);
    }

    /**
     * 后台仅推进已登记操作，不接受客户端工作项、金额或新执行。
     */
    public static final class ClosureScanner {
        private final DefaultWorkspaceLifecycleService lifecycle;

        ClosureScanner(DefaultWorkspaceLifecycleService lifecycle) {
            this.lifecycle = lifecycle;
        }

        @Scheduled(initialDelay = 5000, fixedDelay = 5000)
        public void scan() {
            try {
                lifecycle.advanceUnfinished();
            } catch (RuntimeException failure) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Workspace closure scan unavailable; retrying later", failure);
            }
        }
    }
}
