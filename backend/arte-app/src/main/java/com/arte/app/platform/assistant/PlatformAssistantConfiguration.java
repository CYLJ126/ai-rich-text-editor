package com.arte.app.platform.assistant;

import com.arte.ainew.api.control.AssistantManager;
import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.assistant.DefaultAssistantManager;
import com.arte.ainew.application.context.TextContextService;
import com.arte.ainew.config.NewAiExecutionProperties;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence;
import com.arte.ainew.spi.assistant.AssistantStore;
import com.arte.ainew.spi.assistant.AssistantTrialVerifier;
import com.arte.app.platform.persistence.JdbcAssistantStore;
import com.arte.app.platform.persistence.PlatformJson;
import com.arte.app.platform.persistence.PlatformPersistenceExecutor;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.api.access.ResourceRegistrationService;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.time.Clock;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-assistant.enabled", "platform.identity.enabled",
        "platform.persistence.enabled", "platform.workspace.enabled"}, havingValue = "true")
public class PlatformAssistantConfiguration {

    @Bean
    JdbcAssistantStore assistantStore(@Qualifier("appDataSource") DataSource source,
                                  @Qualifier("platformTransactionRunner") PublicTransactionRunner tx, PlatformJson json) {
        return new JdbcAssistantStore(source, tx, json);
    }

    @Bean
    AssistantAccessAdapter assistantAccessAdapter(AssistantStore store) {
        return new AssistantAccessAdapter(store);
    }

    @Bean
    AssistantManager assistantManager(AssistantStore store, @Qualifier("platformTransactionRunner") PublicTransactionRunner tx,
                                      @Qualifier("platformAuthorizationService") AuthorizationService authorization,
                                      ResourceRegistrationService registration, IdentityProvider identity,
                                      TenantStore tenants, WorkspaceStore workspaces, TenantMembershipStore tenantMembers,
                                      WorkspaceMembershipStore members, PreferenceStore preferences, CommandReceiptStore receipts,
                                      PublicOutboxStore outbox, AuditSink audit, PlatformIdGenerator ids,
                                      @Qualifier("platformWorkspaceClock") Clock clock, ObjectProvider<AssistantTrialVerifier> trials) {
        var locks = new WorkspaceGovernanceLocks(tx, tenants, workspaces, tenantMembers, members, preferences);
        return new DefaultAssistantManager(store, tx, locks, authorization, registration, identity, receipts,
                outbox, audit, ids, clock, trials.getIfAvailable());
    }

    @Bean
    PlatformAssistantExecutionAccess assistantExecutionAccess(JdbcAssistantStore store,
                                                              @Qualifier("platformTransactionRunner") PublicTransactionRunner tx,
                                                              @Qualifier("platformAuthorizationService") AuthorizationService authorization, IdentityProvider identity,
                                                              TenantStore tenants, WorkspaceStore workspaces, TenantMembershipStore tenantMembers, WorkspaceMembershipStore members,
                                                              PreferenceStore preferences, PlatformPersistenceExecutor executor, ObjectProvider<MybatisPayloadPersistence> payloads,
                                                              ObjectProvider<MybatisExecutionPersistence> executions, @Qualifier("platformWorkspaceClock") Clock clock) {
        var locks = new WorkspaceGovernanceLocks(tx, tenants, workspaces, tenantMembers, members, preferences);
        return new PlatformAssistantExecutionAccess(store, tx, locks, authorization, identity, executor, payloads, executions, clock);
    }

    @Bean
    @ConditionalOnProperty(prefix = "arte.ai-new-execution", name = "enabled", havingValue = "true")
    AssistantTrialService assistantTrialService(JdbcAssistantStore store, PlatformAssistantExecutionAccess access,
                                                PlatformPersistenceExecutor executor, MybatisPayloadPersistence payloads, MybatisExecutionPersistence executions,
                                                TextContextService contexts, BindingManager bindings, InvocationCoordinator coordinator,
                                                NewAiProperties properties, NewAiExecutionProperties execution) {
        return new AssistantTrialService(store, access, executor, payloads, executions, contexts, bindings,
                coordinator, properties, execution);
    }
}
