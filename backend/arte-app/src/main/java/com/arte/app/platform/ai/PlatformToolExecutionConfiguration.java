package com.arte.app.platform.ai;

import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.config.NewAiToolApprovalProperties;
import com.arte.ainew.spi.auth.ToolApprovalAccess;
import com.arte.app.platform.ai.tool.LocalTextTools;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.*;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;

/**
 * 正式应用的本地文本工具与执行授权装配；不开启功能开关，不自动发布、建绑或授予角色。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 11:18 ✾
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(NewAiToolApprovalProperties.class)
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-tool.enabled"}, havingValue = "true")
public class PlatformToolExecutionConfiguration {
    @Bean
    LocalTextTools localTextTools(@Qualifier("newAiClock") Clock clock) {
        return new LocalTextTools(clock);
    }

    @Bean
    LocalToolRegistry localToolRegistry(LocalTextTools tools) {
        return tools.registry();
    }

    @Bean
    PlatformToolExecutionAccess toolExecutionAccess(PlatformToolManagementAccess platform, LocalTextTools tools) {
        return new PlatformToolExecutionAccess(platform, tools);
    }

    @Bean
    ToolApprovalAccess toolApprovalAccess(PlatformToolManagementAccess platform, RbacIdentityProvider identities,
                                          @Qualifier("platformTransactionRunner") PublicTransactionRunner transactions, TenantStore tenants, WorkspaceStore workspaces,
                                          TenantMembershipStore tenantMembers, WorkspaceMembershipStore workspaceMembers, PreferenceStore preferences,
                                          LocalTextTools tools, NewAiToolApprovalProperties properties,
                                          @Qualifier("newAiPersistenceScheduler") Scheduler scheduler, @Qualifier("newAiClock") Clock clock) {
        return new PlatformToolApprovalAccess(platform, identities,
                new WorkspaceGovernanceLocks(transactions, tenants, workspaces, tenantMembers, workspaceMembers, preferences),
                transactions, tools, properties, scheduler, clock);
    }
}
