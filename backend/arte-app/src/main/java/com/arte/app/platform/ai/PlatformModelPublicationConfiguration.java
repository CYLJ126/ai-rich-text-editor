package com.arte.app.platform.ai;

import com.arte.ainew.application.model.DefaultModelPublicationService;
import com.arte.ainew.config.NewAiGenerationProperties;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.infrastructure.http.EnvironmentCredentialResolver;
import com.arte.ainew.spi.gateway.ModelGateway;
import com.arte.ainew.spi.model.*;
import com.arte.ainew.spi.persistence.ExecutionReadStore;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformModelPublicationMapper;
import com.arte.app.platform.persistence.mapper.PlatformModelResourceMapper;
import com.arte.app.service.platform.ModelPublicationStoreServiceImpl;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;

/**
 * 资源发布及执行接线
 * <p>
 * 必须显式开启准备服务和 DATABASE 控制面，不替换唯一配置来源。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 20:55 ✾
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arte.ai-new-model-publication", name = "enabled", havingValue = "true")
public class PlatformModelPublicationConfiguration {

    @Bean
    ModelPublicationStore modelPublicationStore(PlatformModelResourceMapper resources, PlatformModelPublicationMapper mapper,
                                                @Qualifier("platformTransactionRunner") PublicTransactionRunner tx, PlatformJson json,
                                                NewAiProperties properties, ModelManagementProperties management,
                                                ObjectProvider<ModelSharingStore> sharing, ModelResourceStore models) {
        if (!properties.enabled() || !"DATABASE".equals(properties.configurationMode())
                || !"appDataSource".equals(properties.dataSourceBean()) || !management.enabled()) {
            throw new IllegalStateException("Personal model publication requires enabled model management and DATABASE AI on appDataSource");
        }
        ModelPublicationStoreServiceImpl store = new ModelPublicationStoreServiceImpl(resources, mapper, tx, json);
        store.sharing(sharing.getIfAvailable(), models);
        return store;
    }

    @Bean
    DefaultModelPublicationService modelPublicationService(ModelResourceStore models, ModelDraftStore drafts, ModelPublicationStore releases,
                                                           ModelConnectionTemplates templates, ReviewedModelPlanSource plans,
                                                           @Qualifier("platformTransactionRunner") PublicTransactionRunner tx,
                                                           IdentityProvider identity, TenantStore tenants, WorkspaceStore workspaces,
                                                           TenantMembershipStore tenantMembers, WorkspaceMembershipStore members,
                                                           PreferenceStore preferences, @Qualifier("platformAuthorizationService") AuthorizationService authorization,
                                                           CommandReceiptStore receipts, PublicOutboxStore outbox, AuditSink audit,
                                                           PlatformIdGenerator ids, @Qualifier("platformWorkspaceClock") Clock clock,
                                                           ObjectProvider<ModelGateway> gateway, ObjectProvider<ModelSharingStore> sharing) {
        DefaultModelPublicationService service = new DefaultModelPublicationService(models, drafts, releases, templates,
                plans,
                tx, identity, tenants, workspaces, tenantMembers, members, preferences, authorization, receipts, outbox, audit, ids, clock, gateway::getIfAvailable);
        service.sharing(sharing.getIfAvailable());
        return service;
    }

    @Bean
    ModelPublicationRuntimeAdapter modelPublicationRuntime(ModelPublicationStore releases, ModelResourceStore models,
                                                           DefaultModelPublicationService publications, IdentityProvider identity,
                                                           @Qualifier("platformAuthorizationService") AuthorizationService authorization,
                                                           @Qualifier("platformWorkspaceClock") Clock clock, ObjectProvider<ModelSelectionSource> selections) {
        ModelPublicationRuntimeAdapter runtime = new ModelPublicationRuntimeAdapter(releases, models, publications, identity, authorization, clock);
        if (selections.getIfAvailable() != null) {
            runtime.selections(selections.getObject());
        }
        return runtime;
    }

    @Bean
    DatabaseModelUsagePreparationService modelUsagePreparationService(DatabaseAiControlService control,
                                                                      DefaultModelPublicationService publications, ModelPublicationRuntimeAdapter runtime,
                                                                      ModelPublicationStore releases, ModelResourceStore models, ModelAclScopeStore scopes,
                                                                      @Qualifier("platformAuthorizationService") AuthorizationService authorization,
                                                                      @Qualifier("platformTransactionRunner") PublicTransactionRunner tx,
                                                                      IdentityProvider identity, CommandReceiptStore receipts, PublicOutboxStore outbox,
                                                                      AuditSink audit, PlatformIdGenerator ids, @Qualifier("platformWorkspaceClock") Clock clock,
                                                                      ObjectProvider<ModelGateway> gateway) {
        return new DatabaseModelUsagePreparationService(control, publications, runtime, releases, models, scopes,
                authorization, tx, identity, receipts, outbox, audit, ids, clock, gateway::getIfAvailable);
    }

    @Bean
    ModelPublicationCredentialResolver modelPublicationCredentialResolver(NewAiGenerationProperties http, ObjectProvider<ExecutionReadStore> executions,
                                                                          ModelPublicationStore releases, ModelResourceStore models, ModelDraftStore drafts,
                                                                          ModelCredentialCipher cipher, DefaultModelPublicationService publications,
                                                                          ObjectProvider<DatabaseAiControlService> control, IdentityProvider identity,
                                                                          @Qualifier("platformTransactionRunner") PublicTransactionRunner tx,
                                                                          @Qualifier("newAiPersistenceScheduler") Scheduler scheduler,
                                                                          @Qualifier("platformWorkspaceClock") Clock clock) {
        return new ModelPublicationCredentialResolver(new EnvironmentCredentialResolver(http), executions::getObject,
                releases, models, drafts, cipher, publications, context -> control.getObject().configurationInCurrentTransaction(context), identity, tx, scheduler, clock);
    }
}
