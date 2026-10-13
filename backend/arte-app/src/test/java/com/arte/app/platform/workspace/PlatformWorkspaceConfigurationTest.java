package com.arte.app.platform.workspace;

import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.platform.persistence.PlatformPersistenceExecutor;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.api.workspace.*;
import com.arte.core.application.workspace.DefaultWorkspaceServices;
import com.arte.core.spi.access.WorkspaceWriteGuard;
import com.arte.core.spi.platform.*;
import com.arte.core.spi.workspace.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

class PlatformWorkspaceConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PlatformWorkspaceConfiguration.class);

    @Test
    void disabledDoesNotRequireIdentityDatabaseRedisOrRegisterServices() {
        runner.run(context -> {
            assertNull(context.getStartupFailure());
            assertTrue(context.getBeansOfType(WorkspaceService.class).isEmpty());
            assertTrue(context.getBeansOfType(ReactiveWorkspaceOperations.class).isEmpty());
        });
    }

    @Test
    void incompletePrerequisitesFailExplicitly() {
        runner.withPropertyValues("arte.platform.workspace.enabled=true").run(context -> {
            assertNotNull(context.getStartupFailure());
            assertTrue(root(context.getStartupFailure()).getMessage().contains("arte.platform.identity.enabled=true"));
        });
    }

    @Test
    void enabledUsesOneServiceGraphAndDoesNotReplaceLegacyBeans() {
        dependencies().withPropertyValues("arte.platform.workspace.enabled=true",
                        "arte.platform.identity.enabled=true", "arte.platform.persistence.enabled=true")
                .withBean("legacyAuthorizationService", com.arte.app.api.rbac.AuthorizationService.class,
                        () -> mock(com.arte.app.api.rbac.AuthorizationService.class))
                .withBean("legacyClock", Clock.class, Clock::systemDefaultZone)
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var services = context.getBean(DefaultWorkspaceServices.class);
                    assertSame(services.personal(), context.getBean(PersonalWorkspaceService.class));
                    assertSame(services.workspaces(), context.getBean(WorkspaceService.class));
                    assertSame(services.preferences(), context.getBean(WorkspacePreferenceService.class));
                    assertSame(services.policies(), context.getBean(WorkspacePolicyService.class));
                    assertSame(services.scopes(), context.getBean(ScopeResolver.class));
                    assertSame(services.authorization(), context.getBean(AuthorizationService.class));
                    assertSame(services.writeGuard(), context.getBean(WorkspaceWriteGuard.class));
                    assertSame(services.receiptQueries(), context.getBean(com.arte.core.api.platform.CommandReceiptQueryService.class));
                    assertEquals(1, context.getBeansOfType(ReactiveWorkspaceOperations.class).size());
                    assertEquals(java.time.ZoneOffset.UTC, context.getBean("platformWorkspaceClock", Clock.class).getZone());
                    assertEquals(1, context.getBeansOfType(com.arte.app.api.rbac.AuthorizationService.class).size());
                    assertTrue(context.getBeansOfType(org.springframework.transaction.PlatformTransactionManager.class).isEmpty());
                    assertTrue(context.getBeansOfType(MembershipService.class).isEmpty());
                    assertTrue(context.getBeansOfType(WorkspaceLifecycleService.class).isEmpty());
                });
    }

    @Test
    void removedPolicyConfigurationAndUnknownPropertiesFailStartup() {
        for (String invalid : java.util.List.of("personal-policy.creation-types=article", "unknown-property=true")) {
            dependencies().withPropertyValues("arte.platform.workspace.enabled=true",
                    "arte.platform.identity.enabled=true", "arte.platform.persistence.enabled=true",
                    "arte.platform.workspace." + invalid).run(context -> assertNotNull(context.getStartupFailure()));
        }
    }

    private ApplicationContextRunner dependencies() {
        return runner.withBean(RbacIdentityProvider.class, () -> mock(RbacIdentityProvider.class))
                .withBean("platformTransactionRunner", PublicTransactionRunner.class, () -> mock(PublicTransactionRunner.class))
                .withBean(TenantStore.class, () -> mock(TenantStore.class))
                .withBean(WorkspaceStore.class, () -> mock(WorkspaceStore.class))
                .withBean(TenantMembershipStore.class, () -> mock(TenantMembershipStore.class))
                .withBean(WorkspaceMembershipStore.class, () -> mock(WorkspaceMembershipStore.class))
                .withBean(PersonalTenantPolicyTemplateStore.class, () -> mock(PersonalTenantPolicyTemplateStore.class))
                .withBean(PolicyStore.class, () -> mock(PolicyStore.class))
                .withBean(PreferenceStore.class, () -> mock(PreferenceStore.class))
                .withBean(CommandReceiptStore.class, () -> mock(CommandReceiptStore.class))
                .withBean(PublicOutboxStore.class, () -> mock(PublicOutboxStore.class))
                .withBean(AuditSink.class, () -> mock(AuditSink.class))
                .withBean(PlatformIdGenerator.class, () -> () -> java.util.UUID.randomUUID().toString())
                .withBean(WorkspaceAccessFactsStore.class, () -> mock(WorkspaceAccessFactsStore.class))
                .withBean(PlatformPersistenceExecutor.class, () -> mock(PlatformPersistenceExecutor.class));
    }

    private Throwable root(Throwable failure) {
        while (failure.getCause() != null) failure = failure.getCause();
        return failure;
    }
}
