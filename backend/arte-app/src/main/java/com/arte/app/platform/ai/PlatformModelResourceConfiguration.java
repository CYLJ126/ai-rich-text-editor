package com.arte.app.platform.ai;

import com.arte.ainew.api.model.ModelResourceService;
import com.arte.ainew.application.model.DefaultModelResourceService;
import com.arte.ainew.spi.model.ModelPublicationSource;
import com.arte.ainew.spi.model.ModelResourceStore;
import com.arte.ainew.spi.model.ModelSharingStore;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.identity.RbacToolPermissionReader;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.api.access.ResourceRegistrationService;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;
import java.util.Set;

/**
 * 内部管理闭环装配；领域提供者只依赖原始 Store，避免与公共授权图形成依赖环。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 22:40 ✾
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "platform.identity.enabled", "platform.persistence.enabled", "platform.workspace.enabled"}, havingValue = "true")
public class PlatformModelResourceConfiguration {

    @Bean
    ModelResourceAccessAdapter modelResourceAccessAdapter(ModelResourceStore store,
                                                          @Qualifier("platformTransactionRunner") PublicTransactionRunner transactions,
                                                          ObjectProvider<ModelSharingStore> sharing) {
        ModelResourceAccessAdapter adapter = new ModelResourceAccessAdapter(store, transactions);
        adapter.sharing(sharing.getIfAvailable());
        return adapter;
    }

    @Bean
    ModelPublicationSource modelPublicationSource() {
        // 实际模型通过审核方案及验证记录发布，旧认领发布入口保持关闭。
        return (actor, scope, source, revision, binding) -> {
            throw new PlatformException(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED, "model-source");
        };
    }

    @Bean
    ModelResourceService modelResourceService(ModelResourceStore store, ModelPublicationSource publications,
                                              @Qualifier("platformTransactionRunner") PublicTransactionRunner tx,
                                              IdentityProvider identity, TenantStore tenants, WorkspaceStore workspaces,
                                              TenantMembershipStore tenantMembers, WorkspaceMembershipStore members,
                                              PreferenceStore preferences, @Qualifier("platformAuthorizationService") AuthorizationService authorization,
                                              ResourceRegistrationService registration, CommandReceiptStore receipts,
                                              PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids,
                                              @Qualifier("platformWorkspaceClock") Clock clock, ObjectProvider<ModelSharingStore> sharing,
                                              RbacIdentityProvider identities, @Qualifier("appDataSource") DataSource source) {
        DefaultModelResourceService service = new DefaultModelResourceService(store, publications, tx, identity, tenants,
                workspaces, tenantMembers, members, preferences, authorization, registration, receipts, outbox, audit, ids, clock);
        service.sharing(sharing.getIfAvailable());
        RbacToolPermissionReader permissions = new RbacToolPermissionReader(source);
        service.tenantCreation(actor -> {
            String name = identities.currentSubjectName(actor.principal().subjectId()).orElseThrow(() ->
                    new PlatformException(PlatformErrorCodeEnum.UNAUTHENTICATED, "tenant-model"));
            Set<String> current = permissions.current(name);
            if (!current.contains("tenantModel:create") && !current.contains("aiConfiguration:manage") && !current.contains("admin")) {
                throw new PlatformException(PlatformErrorCodeEnum.ACCESS_DENIED, "tenant-model");
            }
        });
        return service;
    }
}
