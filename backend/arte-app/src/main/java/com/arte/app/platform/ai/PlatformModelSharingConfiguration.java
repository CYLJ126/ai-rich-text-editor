package com.arte.app.platform.ai;

import com.arte.ainew.application.model.DefaultModelSharingService;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.spi.model.ModelDraftStore;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.ainew.spi.model.ModelSharingStore;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.mapper.PlatformModelResourceMapper;
import com.arte.app.platform.persistence.mapper.PlatformModelSharingMapper;
import com.arte.app.service.platform.ModelSharingStoreServiceImpl;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import java.time.Clock;

/**
 * 显式开启租户模型共享；不开启时不读取新表、不改变旧个人模型。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/01 19:25 ✾
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arte.ai-new-model-sharing", name = "enabled", havingValue = "true")
public class PlatformModelSharingConfiguration {

    @Bean
    ModelSharingStore modelSharingStore(PlatformModelResourceMapper resources, PlatformModelSharingMapper mapper,
                                        @Qualifier("platformTransactionRunner") PublicTransactionRunner tx, PlatformJson json,
                                        NewAiProperties properties, Environment environment) {
        if (!"DATABASE".equals(properties.configurationMode()) || !environment.getProperty("arte.ai-new-model-publication.enabled", Boolean.class, false)) {
            throw new IllegalStateException("Tenant models require DATABASE and enabled model publication");
        }
        return new ModelSharingStoreServiceImpl(resources, mapper, tx, json);
    }

    @Bean
    DefaultModelSharingService modelSharingService(ModelResourceStore models, ModelDraftStore drafts, ModelSharingStore store,
                                                   @Qualifier("platformAuthorizationService") AuthorizationService authorization,
                                                   @Qualifier("platformTransactionRunner") PublicTransactionRunner tx,
                                                   IdentityProvider identity, TenantStore tenants, WorkspaceStore workspaces,
                                                   TenantMembershipStore tenantMembers, WorkspaceMembershipStore members,
                                                   PreferenceStore preferences, CommandReceiptStore receipts, PublicOutboxStore outbox,
                                                   AuditSink audit, PlatformIdGenerator ids, @Qualifier("platformWorkspaceClock") Clock clock) {
        return new DefaultModelSharingService(models, drafts, store, authorization, tx, identity, tenants, workspaces, tenantMembers, members, preferences, receipts, outbox, audit, ids, clock);
    }
}
