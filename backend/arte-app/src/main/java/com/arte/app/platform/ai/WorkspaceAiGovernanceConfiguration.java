package com.arte.app.platform.ai;

import com.arte.ainew.spi.model.ModelPublicationStore;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformAiControlMapper;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.spi.access.WorkspaceWriteGuard;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.WorkspaceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * DATABASE 空间治理及运行时精确选择共用同一持久化权威。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/03 15:30 ✾
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arte.ai-new", name = "configuration-mode", havingValue = "DATABASE")
public class WorkspaceAiGovernanceConfiguration {

    @Bean
    WorkspaceAiGovernanceService workspaceAiGovernanceService(PlatformAiControlMapper mapper, PlatformJson json,
                                                              @Qualifier("platformTransactionRunner") PublicTransactionRunner tx, WorkspaceWriteGuard guards,
                                                              WorkspaceStore workspaces, @Qualifier("platformAuthorizationService") AuthorizationService authorization,
                                                              ObjectProvider<DatabaseAiControlService> control, ObjectProvider<ModelPublicationStore> publications,
                                                              ObjectProvider<ModelResourceStore> resources, ObjectProvider<ModelPublicationRuntimeAdapter> runtime,
                                                              IdentityProvider identity, CommandReceiptStore receipts, PublicOutboxStore outbox, AuditSink audit,
                                                              PlatformIdGenerator ids, @Qualifier("platformWorkspaceClock") Clock clock) {
        return new WorkspaceAiGovernanceService(mapper, json, tx, guards, workspaces, authorization, control, publications,
                resources, runtime, identity, receipts, outbox, audit, ids, clock);
    }
}
