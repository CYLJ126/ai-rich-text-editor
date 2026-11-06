package com.arte.app.platform.ai;

import com.arte.ainew.infrastructure.http.GenerationException;
import com.arte.ainew.infrastructure.http.HttpEgressPolicy;
import com.arte.ainew.spi.model.ModelPublicationStore;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformAiControlMapper;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.spi.access.WorkspaceWriteGuard;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.PolicyStore;
import com.arte.core.spi.workspace.WorkspaceStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * 装配 DATABASE 模式下的空间 AI 治理和模型出口策略。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/03 15:30 ✾
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arte.ai-new", name = "configuration-mode", havingValue = "DATABASE")
public class WorkspaceAiGovernanceConfiguration {

    @Bean("newAiModelEgressPolicy")
    HttpEgressPolicy modelEgressPolicy(DatabaseAiControlService control) {
        return new HttpEgressPolicy(() -> {
            try {
                return control.modelOrigins(false);
            } catch (RuntimeException failure) {
                throw GenerationException.transientBeforeSend("EGRESS_POLICY_UNAVAILABLE");
            }
        });
    }

    @Bean
    WorkspaceAiGovernanceService workspaceAiGovernanceService(PlatformAiControlMapper mapper, PlatformJson json,
                                                              @Qualifier("platformTransactionRunner") PublicTransactionRunner tx, WorkspaceWriteGuard guards,
                                                              WorkspaceStore workspaces, PolicyStore policies, @Qualifier("platformAuthorizationService") AuthorizationService authorization,
                                                              ObjectProvider<DatabaseAiControlService> control, ObjectProvider<ModelPublicationStore> publications,
                                                              ObjectProvider<ModelResourceStore> resources, ObjectProvider<ModelPublicationRuntimeAdapter> runtime,
                                                              IdentityProvider identity, CommandReceiptStore receipts, PublicOutboxStore outbox, AuditSink audit,
                                                              PlatformIdGenerator ids, @Qualifier("platformWorkspaceClock") Clock clock) {
        return new WorkspaceAiGovernanceService(mapper, json, tx, guards, workspaces, policies, authorization, control, publications,
                resources, runtime, identity, receipts, outbox, audit, ids, clock);
    }
}
