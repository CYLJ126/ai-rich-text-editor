package com.arte.app.platform.ai;

import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.config.NewAiToolApprovalProperties;
import com.arte.ainew.infrastructure.http.HttpHealthToolAdapter;
import com.arte.ainew.infrastructure.http.McpHealthToolAdapter;
import com.arte.ainew.spi.auth.ToolApprovalAccess;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ToolApprovalStore;
import com.arte.app.platform.ai.tool.*;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.api.workspace.WorkspaceService;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.spi.platform.PublicTransactionRunner;
import com.arte.core.spi.workspace.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;

/**
 * 正式应用的本地文本工具与执行授权装配；不开启功能开关，不自动发布、建绑或授予角色。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 11:18 ✾
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({NewAiToolApprovalProperties.class, LocalToolWriteProperties.class})
@ConditionalOnProperty(prefix = "arte", name = {"ai-new.enabled", "ai-new-tool.enabled"}, havingValue = "true")
public class PlatformToolExecutionConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "arte.ai-new-tool-chat", name = "enabled", havingValue = "true")
    com.arte.ainew.spi.auth.ToolChatAccess toolChatAccess(PlatformToolManagementAccess platform,
                                                          com.arte.ainew.spi.auth.ToolInvocationAccess controls) {
        return (current, cancelling) -> {
            var owner = com.arte.ainew.common.execution.ExecutionOwner.from(current);
            reactor.core.publisher.Mono<Runnable> check = cancelling
                    ? controls.authorizeOwner(owner, current, com.arte.ainew.spi.auth.ToolInvocationAccess.Operation.CANCEL)
                    .map(p -> p::requireInCurrentTransaction)
                    : platform.authorizeUse(current).map(p -> () -> p.requireInCurrentTransaction(java.util.Set.of("ai:invoke")));
            return check.map(guard -> snapshot -> {
                if (!owner.equals(com.arte.ainew.common.execution.ExecutionOwner.from(snapshot.context()))
                        || !current.authorization().principal().equals(snapshot.context().authorization().principal()))
                    throw new org.springframework.security.access.AccessDeniedException("Tool chat owner mismatch");
                guard.run();
            });
        };
    }

    @Bean
    LocalTextTools localTextTools(@Qualifier("newAiClock") Clock clock) {
        return new LocalTextTools(clock);
    }

    @Bean
    LocalWorkspaceTools localWorkspaceTools(LocalToolWriteProperties properties, WorkspaceService workspaces,
                                            PlatformToolManagementAccess platform, RbacIdentityProvider identities, AuthorizationService authorization,
                                            @Qualifier("platformTransactionRunner") PublicTransactionRunner transactions, TenantStore tenants, WorkspaceStore spaces,
                                            TenantMembershipStore tenantMembers, WorkspaceMembershipStore workspaceMembers, PreferenceStore preferences,
                                            ObjectProvider<ToolApprovalStore> approvals, ExecutionRecordCodec codec, @Qualifier("newAiClock") Clock clock) {
        return new LocalWorkspaceTools(properties, workspaces, platform, identities, authorization,
                new WorkspaceGovernanceLocks(transactions, tenants, spaces, tenantMembers, workspaceMembers, preferences),
                transactions, approvals, codec, clock);
    }

    /**
     * 历史 v1 的收尾查询独立登记，不改变已发布的执行契约，也不依赖当前写开关或原用户权限。
     */
    @Bean
    LocalWorkspaceOutcomeQuery localWorkspaceOutcomeQuery(LocalWorkspaceTools contract,
                                                          com.arte.core.spi.platform.CommandReceiptStore receipts,
                                                          ObjectProvider<com.arte.ainew.spi.persistence.ToolOutcomeStore> outcomes) {
        return new LocalWorkspaceOutcomeQuery(contract, receipts, outcomes);
    }

    @Bean
    @Primary
    LocalToolPolicy platformLocalToolPolicy(LocalTextTools text, LocalWorkspaceTools writes, ObjectProvider<HttpHealthToolAdapter> http,
                                            ObjectProvider<McpHealthToolAdapter> mcp) {
        return new LocalToolPolicy() {
            public void requireFree(com.arte.ainew.pojo.tool.ToolAdmissionInput input, com.arte.ainew.pojo.tool.ToolBindingResolution binding) {
                if (binding.tool().target() instanceof com.arte.ainew.pojo.control.ExecutionTarget.Remote) {
                    if (binding.tool().mcp()) {
                        var remoteMcp = mcp.getIfAvailable();
                        if (remoteMcp == null)
                            throw new org.springframework.security.access.AccessDeniedException("MCP tool disabled");
                        remoteMcp.requireFree(input, binding);
                        return;
                    }
                    var remote = http.getIfAvailable();
                    if (remote == null)
                        throw new org.springframework.security.access.AccessDeniedException("HTTP tool disabled");
                    remote.requireFree(input, binding);
                } else if (binding.tool().tool().definition().equals(LocalWorkspaceTools.TOOL))
                    writes.requireFree(input, binding);
                else text.requireFree(input, binding);
            }

            public void requireResource(com.arte.ainew.pojo.tool.ToolAdmissionInput input, com.arte.ainew.common.execution.ExecutionContext context,
                                        com.arte.ainew.pojo.tool.ToolBindingResolution binding) {
                if (binding.tool().tool().definition().equals(LocalWorkspaceTools.TOOL))
                    writes.requireResource(input, context, binding);
            }
        };
    }

    @Bean
    LocalToolRegistry localToolRegistry(LocalTextTools tools, LocalWorkspaceTools writes, LocalToolWriteProperties properties,
                                        ObjectProvider<HttpHealthToolAdapter> http,
                                        ObjectProvider<McpHealthToolAdapter> mcp) {
        var registrations = new java.util.ArrayList<>(tools.registry().registrations());
        if (properties.enabled()) registrations.add(writes.registration());
        http.ifAvailable(remote -> registrations.add(remote.registration()));
        mcp.ifAvailable(remote -> registrations.add(remote.registration()));
        return new LocalToolRegistry(registrations);
    }

    @Bean
    @ConditionalOnProperty(prefix = "arte.ai-new-tool-billing", name = "enabled", havingValue = "true")
    PlatformPaidToolAccess paidToolAccess(PlatformToolManagementAccess platform, LocalToolRegistry registry,
                                          com.arte.ainew.config.NewAiProperties properties,
                                          ObjectProvider<com.arte.ainew.spi.auth.AiControlConfigurationSource> sources) {
        return new PlatformPaidToolAccess(platform, registry, properties, sources);
    }

    @Bean
    PlatformToolExecutionAccess toolExecutionAccess(PlatformToolManagementAccess platform, LocalToolPolicy tools, ObjectProvider<PlatformPaidToolAccess> paid) {
        return new PlatformToolExecutionAccess(platform, tools, paid.getIfAvailable());
    }

    @Bean
    ToolApprovalAccess toolApprovalAccess(PlatformToolManagementAccess platform, RbacIdentityProvider identities,
                                          @Qualifier("platformTransactionRunner") PublicTransactionRunner transactions, TenantStore tenants, WorkspaceStore workspaces,
                                          TenantMembershipStore tenantMembers, WorkspaceMembershipStore workspaceMembers, PreferenceStore preferences,
                                          LocalToolPolicy tools, NewAiToolApprovalProperties properties,
                                          @Qualifier("newAiPersistenceScheduler") Scheduler scheduler, @Qualifier("newAiClock") Clock clock) {
        return new PlatformToolApprovalAccess(platform, identities,
                new WorkspaceGovernanceLocks(transactions, tenants, workspaces, tenantMembers, workspaceMembers, preferences),
                transactions, tools, properties, scheduler, clock);
    }
}
