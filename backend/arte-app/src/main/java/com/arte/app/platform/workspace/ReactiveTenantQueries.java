package com.arte.app.platform.workspace;

import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.persistence.PlatformPersistenceExecutor;
import com.arte.core.api.workspace.TenantGovernanceService;
import com.arte.core.api.workspace.TenantQueryService;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.workspace.TenantBrowseQuery;
import com.arte.core.pojo.workspace.TenantCommands;
import com.arte.core.pojo.workspace.TenantDirectoryViews;
import com.arte.core.pojo.workspace.WorkspaceViews;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

/**
 * 在现有身份和数据库调度边界内执行租户查询与治理命令。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/05 13:11 ✾
 */
public final class ReactiveTenantQueries {

    private final PlatformAuthenticatedExecutor authenticated;
    private final TenantQueryService tenants;
    private final TenantDirectoryBrowser browser;
    private final TenantGovernanceService governance;

    public ReactiveTenantQueries(RbacIdentityProvider identities, PlatformPersistenceExecutor executor,
                                 TenantQueryService tenants, TenantDirectoryBrowser browser, TenantGovernanceService governance) {
        this.authenticated = new PlatformAuthenticatedExecutor(identities, executor);
        this.tenants = tenants;
        this.browser = browser;
        this.governance = governance;
    }

    public Mono<TenantDirectoryBrowser.Page> browse(Authentication authentication, String token, TenantBrowseQuery query) {
        return authenticated.call(authentication, actor -> browser.browse(actor, token, query));
    }

    public Mono<TenantDirectoryViews.Detail> detail(Authentication authentication, String tenantId) {
        return authenticated.call(authentication, actor -> tenants.getTenant(actor, tenantId));
    }

    public Mono<CommandReceipt<WorkspaceViews.TenantView>> createOrganization(Authentication authentication,
                                                                              TenantCommands.CreateOrganization command) {
        return authenticated.call(authentication, actor -> governance.createOrganization(actor, command));
    }

    public Mono<CommandReceipt<WorkspaceViews.TenantView>> updateTenant(Authentication authentication, TenantCommands.UpdateTenant command) {
        return authenticated.call(authentication, actor -> governance.updateTenant(actor, command));
    }

    public Mono<TenantDirectoryViews.CreationOptions> creationOptions(Authentication authentication) {
        return authenticated.call(authentication, tenants::getCreationOptions);
    }
}
