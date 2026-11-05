package com.arte.app.platform.ai;

import com.arte.ainew.api.model.ModelDraftService;
import com.arte.ainew.application.model.DefaultModelDraftService;
import com.arte.ainew.config.NewAiGenerationProperties;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.infrastructure.http.HttpEgressPolicy;
import com.arte.ainew.infrastructure.http.HttpModelConnectionValidator;
import com.arte.ainew.spi.auth.AiControlConfigurationSource;
import com.arte.ainew.spi.model.*;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformModelDraftMapper;
import com.arte.app.platform.persistence.mapper.PlatformModelResourceMapper;
import com.arte.app.service.platform.ModelDraftStoreServiceImpl;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arte", name = {"ai-new-model-management.enabled", "ai-new.enabled", "platform.identity.enabled", "platform.persistence.enabled", "platform.workspace.enabled"}, havingValue = "true")
@EnableConfigurationProperties({ModelManagementProperties.class, NewAiGenerationProperties.class})
public class PlatformModelDraftConfiguration {

    @Bean
    ModelDraftStore modelDraftStore(PlatformModelDraftMapper mapper, PlatformModelResourceMapper resources,
                                    @Qualifier("platformTransactionRunner") PublicTransactionRunner tx, PlatformJson json) {
        return new ModelDraftStoreServiceImpl(resources, mapper, tx, json);
    }

    @Bean
    ModelCredentialCipher modelCredentialCipher(ModelManagementProperties properties) {
        return new Sm4ModelCredentialCipher(properties);
    }

    @Bean
    ModelConnectionTemplates modelConnectionTemplates(NewAiProperties fixed, ObjectProvider<AiControlConfigurationSource> source,
                                                      PolicyStore policies, NewAiGenerationProperties http) {
        return new ReviewedModelConnectionTemplates(() -> {
            var selected = source.getIfAvailable();
            if (selected != null) return selected.definitions();
            if ("DATABASE".equals(fixed.configurationMode()))
                throw new IllegalStateException("DATABASE model templates require a database configuration source");
            return fixed;
        }, policies, new HttpEgressPolicy(http));
    }

    @Bean
    @ConditionalOnMissingBean(ModelConnectionValidator.class)
    ModelConnectionValidator modelConnectionValidator(NewAiGenerationProperties properties) {
        return new HttpModelConnectionValidator(properties);
    }

    @Bean
    ReviewedModelPlanSource reviewedModelPlans(ObjectProvider<DatabaseAiControlService> control) {
        return () -> control.getObject().reviewedModelCatalog();
    }

    @Bean
    ModelDraftService modelDraftService(ModelResourceStore models, ModelDraftStore store, ObjectProvider<ModelPublicationStore> publications, ModelConnectionTemplates templates,
                                        ModelCredentialCipher cipher, ReviewedModelPlanSource plans, ObjectProvider<com.arte.ainew.spi.gateway.ModelGateway> gateway, @Qualifier("platformTransactionRunner") PublicTransactionRunner tx,
                                        IdentityProvider identity, TenantStore tenants, WorkspaceStore workspaces, TenantMembershipStore tenantMembers,
                                        WorkspaceMembershipStore members, PreferenceStore preferences, @Qualifier("platformAuthorizationService") AuthorizationService authorization,
                                        CommandReceiptStore receipts, PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids, @Qualifier("platformWorkspaceClock") Clock clock) {
        return new DefaultModelDraftService(models, store, publications::getIfAvailable, templates, cipher, plans, gateway::getIfAvailable, tx, identity, tenants, workspaces, tenantMembers,
                members, preferences, authorization, receipts, outbox, audit, ids, clock);
    }
}
