package com.arte.app.platform.persistence;

import com.arte.ainew.api.model.ModelAclMigrationService;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.infrastructure.control.FixedAiResourceEntitlementService;
import com.arte.ainew.pojo.control.AiEntitlement;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.model.ModelPublication;
import com.arte.ainew.pojo.model.ModelResource;
import com.arte.ainew.pojo.model.ModelResourceCommands;
import com.arte.app.platform.ai.PlatformModelAclAccess;
import com.arte.app.platform.ai.PlatformModelAclMigrationService;
import com.arte.app.platform.identity.RbacFixedExecutionAuthorizationResolver;
import com.arte.app.platform.identity.RbacToolPermissionReader;
import com.arte.app.service.platform.ModelAclScopeStoreServiceImpl;
import com.arte.core.enums.platform.CommandDeliveryEnum;
import com.arte.core.pojo.access.GrantRecipient;
import com.arte.core.pojo.access.ResourceAccessCommands;
import com.arte.core.pojo.access.ResourceAccessViews;
import com.arte.core.pojo.access.VisibilityGrant;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.pojo.reference.DefinitionRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;

import static com.arte.app.platform.persistence.ModelResourceIntegrationTest.*;
import static com.arte.core.constant.platform.PlatformActions.MODEL_USE;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实空间、RBAC、Mapper、迁移服务和执行资格，H2 与可选 MySQL 共用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/31 12:00 ✾
 */
class ModelAclExecutionIntegrationTest {

    final ModelResourceIntegrationTest f = new ModelResourceIntegrationTest();
    ModelAclScopeStoreServiceImpl scopes;
    PlatformModelAclAccess access;
    PlatformModelAclMigrationService migration;
    ModelResource model;
    ExecutionContext context;
    static final Duration WAIT = Duration.ofSeconds(10);

    @BeforeEach
    void setup() throws Exception {
        f.setup();
        String sql = Files.readString(Path.of("scripts", "arte-ai-model-acl-scope-ddl-mysql.sql"));
        if (f.f.mysql == null) {
            sql = sql.replaceAll("(?i)CHARACTER SET [a-z0-9_]+ COLLATE [a-z0-9_]+", "").replaceAll("(?i)ENGINE=InnoDB", "").replaceAll("(?im)^SET\\s+[^;]*;", "");
        }
        try (java.sql.Connection connection = f.f.source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        scopes = f.stores.aclScopes();
        access = new PlatformModelAclAccess(scopes, f.store, f.identity, f.f.tx, f.platform.authorization(), f.stores.tenants(), f.stores.workspaces(),
                f.stores.tenantMembers(), f.stores.workspaceMembers(), f.stores.preferences(), Schedulers.immediate(), CLOCK);
        migration = migration(properties(Set.of(V1.id())));
        model = f.create("model");
        f.models.associate(f.f.actor, f.association("v1", model, V1, ModelPublication.Source.FIXED, null));
        model = f.current(model);
        NewAiProperties.Grant grant = properties(Set.of(V1.id())).grants().getFirst();
        context = new ExecutionContext("execution", "trace", new ExecutionAuthorization(ExecutionPrincipal.from(f.f.actor.principal(), "alice"),
                f.scope.tenantId(), f.scope.workspaceId(), grant.scopes(), grant.grantRef()), Instant.now().plusSeconds(180), null, null, f.seed.releaseRef(), "execution-key");
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    NewAiProperties properties(Set<String> ids) {
        NewAiProperties.Grant grant = new NewAiProperties.Grant("alice", f.f.actor.principal().subjectId(), ExecutionPrincipal.Kind.USER,
                f.scope.tenantId(), f.scope.workspaceId(), "legacy-grant", true, Set.of(AdmissionAuthorization.INVOKE), ids, Set.of());
        return new NewAiProperties(false, "appDataSource", f.seed.releaseRef(), null, null, List.of(grant), f.seed.capabilities(), f.seed.bindings(), f.seed.connections(), f.seed.rates(), List.of());
    }

    PlatformModelAclMigrationService migration(NewAiProperties properties) {
        return new PlatformModelAclMigrationService(scopes, f.store, properties, f.catalog.mapper(), f.f.json, f.identity, new RbacToolPermissionReader(f.f.source),
                f.f.tx, f.platform.authorization(), f.stores.tenants(), f.stores.workspaces(), f.stores.tenantMembers(), f.stores.workspaceMembers(),
                f.stores.preferences(), f.stores.receipts(), f.stores.outbox(), f.stores.audit(), () -> UUID.randomUUID().toString(), CLOCK);
    }

    FixedAiResourceEntitlementService service(NewAiProperties properties) {
        return new FixedAiResourceEntitlementService(properties, new AdmissionAuthorization(new RbacFixedExecutionAuthorizationResolver(f.identity, properties, Schedulers.immediate()), properties, CLOCK), access);
    }

    FixedAiResourceEntitlementService service() {
        return service(properties(Set.of(V1.id())));
    }

    ModelAclMigrationService.Migrate command(String key, long expected, Set<DefinitionRef> refs) {
        return new ModelAclMigrationService.Migrate(f.meta(key), f.scope, AiEntitlement.Source.FIXED, refs, expected);
    }

    void migrate() {
        migration.migrate(f.f.actor, command("migrate", 0, Set.of(V1)));
    }

    AiEntitlement resolve() {
        return service().resolve(V1, CAP, context).block(WAIT);
    }

    List<AiEntitlement> discover() {
        return service().discover(CapabilityDescriptor.Kind.GENERATION, 256, context).collectList().block(WAIT);
    }

    void revoke() {
        ResourceAccessViews.GrantView use = f.platform.resources().listGrants(f.f.actor, f.scope, model.resource(), new PageQuery(1, 100)).records().stream()
                .filter(value -> value.action().equals(MODEL_USE)).findFirst().orElseThrow();
        long version = f.stores.grants().findAccess(model.resource()).orElseThrow().aclVersion();
        f.platform.resources().revoke(f.f.actor, new ResourceAccessCommands.RevokeGrant(f.meta("revoke"), f.scope, model.resource(), use.id(), version, use.version()));
    }

    void current(AiEntitlement expected) {
        f.f.tx.required(() -> {
            service().requireInCurrentTransaction(expected, context);
            return null;
        });
    }

    static void await(Future<?> future) {
        try {
            future.get(10, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    @Test
    void unMigratedScopeKeepsLegacyAndExactVersions() {
        assertNull(resolve().modelAccess());
        assertEquals(2, discover().size());
        revoke();
        assertNull(resolve().modelAccess());
        current(resolve());
        assertEquals(0, f.count("arte_ai_model_acl_scope"));
    }

    @Test
    void migratedScopeUsesOnlyMappedAllowlistAndPreservesOriginalOwner() {
        migrate();
        AiEntitlement result = resolve();
        assertEquals(List.of(V1), discover().stream().map(value -> value.binding().definition()).toList());
        assertEquals(model.resource(), result.modelAccess().resource());
        assertEquals(ModelPublication.Source.FIXED, result.modelAccess().definitionSource());
        assertEquals(f.f.actor.principal().subjectId(), result.executionOwner().subjectId());
        assertEquals(f.scope.workspaceId(), result.executionOwner().workspaceId());
        assertTrue(result.grantRef().startsWith("model-acl:"));
        assertEquals(f.seed.connections().getFirst().credential(), result.connection().credential());
        current(result);
    }

    @Test
    void aclCanAllowWithoutLegacyBindingIdsButCannotMintBudget() {
        migrate();
        FixedAiResourceEntitlementService noIds = service(properties(Set.of()));
        assertNotNull(noIds.resolve(V1, CAP, context).block(WAIT).modelAccess());
        assertTrue(properties(Set.of()).grants().getFirst().budgetRefs().isEmpty());
    }

    @Test
    void revokedAclCannotFallBackToOldGrant() {
        migrate();
        AiEntitlement before = resolve();
        revoke();
        assertTrue(discover().isEmpty());
        assertThrows(RuntimeException.class, this::resolve);
        assertThrows(RuntimeException.class, () -> current(before));
    }

    @Test
    void disabledResourceCannotBeDiscoveredResolvedOrCommitted() {
        migrate();
        AiEntitlement before = resolve();
        f.models.disable(f.f.actor, new ModelResourceCommands.Disable(f.meta("disable"), f.scope, model.resource(), model.version()));
        assertTrue(discover().isEmpty());
        assertThrows(RuntimeException.class, this::resolve);
        assertThrows(RuntimeException.class, () -> current(before));
    }

    @Test
    void emptyManifestMeansDenyAllAndDoesNotRevertToLegacy() {
        migrate();
        AiEntitlement before = resolve();
        migration.migrate(f.f.actor, command("close", 1, Set.of()));
        assertTrue(discover().isEmpty());
        assertThrows(RuntimeException.class, this::resolve);
        assertThrows(RuntimeException.class, () -> current(before));
        assertEquals(2, scopes.find(f.scope).orElseThrow().version());
    }

    @Test
    void migrationHasCasReceiptAuditAndEpochWithoutAutomaticWrites() {
        long epoch = f.stores.workspaces().find(f.scope).orElseThrow().authEpoch();
        long audit = f.count("arte_platform_audit"), outbox = f.count("arte_platform_outbox");
        migrate();
        assertEquals(epoch + 1, f.stores.workspaces().find(f.scope).orElseThrow().authEpoch());
        assertEquals(audit + 1, f.count("arte_platform_audit"));
        assertEquals(outbox + 1, f.count("arte_platform_outbox"));
        assertEquals(CommandDeliveryEnum.REPLAYED, migration.migrate(f.f.actor, command("migrate", 0, Set.of(V1))).delivery());
        assertThrows(RuntimeException.class, () -> migration.migrate(f.f.actor, command("stale", 0, Set.of(V1))));
        assertThrows(RuntimeException.class, () -> migration.migrate(f.f.actor, command("migrate", 0, Set.of())));
        assertEquals(1, scopes.find(f.scope).orElseThrow().version());
    }

    @Test
    void migrationRequiresConfigPermissionAndPublishedMappings() {
        assertThrows(RuntimeException.class, () -> migration.migrate(f.f.actor, command("missing", 0, Set.of(V2))));
        assertEquals(0, f.count("arte_ai_model_acl_scope"));
        f.f.jdbc.update("DELETE FROM arte_rbac_relation");
        assertThrows(RuntimeException.class, this::migrate);
        assertEquals(0, f.count("arte_ai_model_acl_scope"));
    }

    @Test
    void missingMappingAndUnavailableAuthorityNeverUseLegacyGrant() {
        migrate();
        f.f.jdbc.update("DELETE FROM arte_ai_model_version");
        assertThrows(RuntimeException.class, this::discover);
        assertThrows(RuntimeException.class, this::resolve);
    }

    @Test
    void brokenOrMissingMigrationStorageNeverLooksLikeUnMigrated() {
        AiEntitlement expected = resolve();
        f.f.jdbc.execute("DROP TABLE arte_ai_model_acl_scope");
        assertThrows(RuntimeException.class, this::discover);
        assertThrows(RuntimeException.class, () -> current(expected));
    }

    @Test
    void sameRefChangedConnectionCannotReusePublishedModelPermission() {
        migrate();
        NewAiProperties old = properties(Set.of(V1.id()));
        ConnectionDefinition connection = old.connections().getFirst();
        ConnectionDefinition changed = new ConnectionDefinition(connection.definition(), connection.providerId(), connection.protocol(), java.net.URI.create("https://other.invalid/v1"),
                connection.credential(), connection.state(), connection.connectTimeout(), connection.responseTimeout(), connection.maxResponseBytes());
        NewAiProperties altered = new NewAiProperties(false, "appDataSource", old.releaseRef(), null, null, old.grants(), old.capabilities(), old.bindings(), List.of(changed), old.rates(), old.budgets());
        assertThrows(RuntimeException.class, () -> service(altered).resolve(V1, CAP, context).block(WAIT));
    }

    @Test
    void privateSharedAndClosedModelsUseTheCallingMembersIdentity() {
        f.f.jdbc.update("INSERT INTO arte_rbac_user VALUES(2,'bob',1,?)", java.sql.Timestamp.valueOf("2026-10-30 10:00:00"));
        Principal bob = f.identity.resolveAuthenticatedName("bob").orElseThrow();
        f.f.tx.required(() -> {
            com.arte.core.application.workspace.WorkspaceGovernanceLocks locks = new com.arte.core.application.workspace.WorkspaceGovernanceLocks(f.f.tx,
                    f.stores.tenants(), f.stores.workspaces(), f.stores.tenantMembers(), f.stores.workspaceMembers(), f.stores.preferences());
            locks.tenant(bob.subjectId(), f.scope.tenantId());
            assertTrue(f.stores.tenantMembers().insert(new com.arte.core.pojo.workspace.TenantMembership("bob-tenant", f.scope.tenantId(), bob.subjectId(),
                    com.arte.core.enums.workspace.TenantRoleEnum.MEMBER, com.arte.core.enums.workspace.MembershipStateEnum.ACTIVE, 0, 0)));
            assertTrue(f.stores.workspaceMembers().insert(new com.arte.core.pojo.workspace.WorkspaceMembership("bob-space", f.scope, bob.subjectId(),
                    com.arte.core.enums.workspace.WorkspaceRoleEnum.MEMBER, com.arte.core.enums.workspace.MembershipStateEnum.ACTIVE, 0, 0)));
            return null;
        });
        migrate();
        NewAiProperties old = properties(Set.of(V1.id()));
        NewAiProperties.Grant grant = new NewAiProperties.Grant("bob", bob.subjectId(), ExecutionPrincipal.Kind.USER, f.scope.tenantId(), f.scope.workspaceId(), "bob-grant", true,
                Set.of(AdmissionAuthorization.INVOKE), Set.of(V1.id()), Set.of());
        NewAiProperties properties = new NewAiProperties(false, "appDataSource", old.releaseRef(), null, null, List.of(grant), old.capabilities(), old.bindings(), old.connections(), old.rates(), List.of());
        ExecutionContext bobContext = new ExecutionContext("bob-execution", "trace", new ExecutionAuthorization(ExecutionPrincipal.from(bob, "bob"), f.scope.tenantId(), f.scope.workspaceId(), grant.scopes(), grant.grantRef()),
                context.deadline(), null, null, context.releaseRef(), "bob-operation");
        FixedAiResourceEntitlementService service = service(properties);
        assertTrue(service.discover(CapabilityDescriptor.Kind.GENERATION, 256, bobContext).collectList().block(WAIT).isEmpty());
        long acl = f.stores.grants().findAccess(model.resource()).orElseThrow().aclVersion();
        f.platform.resources().changeVisibility(f.f.actor, new ResourceAccessCommands.ChangeVisibility(f.meta("share"), f.scope, model.resource(), acl,
                com.arte.core.enums.access.ResourceVisibilityEnum.SPACE_SHARED, List.of(new VisibilityGrant(new GrantRecipient.Space(f.scope), Set.of(MODEL_USE), null))));
        AiEntitlement shared = service.resolve(V1, CAP, bobContext).block(WAIT);
        assertEquals(bob.subjectId(), shared.executionOwner().subjectId());
        assertEquals(model.resource(), shared.modelAccess().resource());
        f.f.tx.required(() -> {
            service.requireInCurrentTransaction(shared, bobContext);
            return null;
        });
        acl = f.stores.grants().findAccess(model.resource()).orElseThrow().aclVersion();
        f.platform.resources().changeVisibility(f.f.actor, new ResourceAccessCommands.ChangeVisibility(f.meta("private"), f.scope, model.resource(), acl,
                com.arte.core.enums.access.ResourceVisibilityEnum.EXPLICIT, List.of()));
        assertThrows(RuntimeException.class, () -> service.resolve(V1, CAP, bobContext).block(WAIT));
        assertThrows(RuntimeException.class, () -> f.f.tx.required(() -> {
            service.requireInCurrentTransaction(shared, bobContext);
            return null;
        }));
    }

    @Test
    void finalGateRequiresOriginalWritableTransaction() {
        migrate();
        AiEntitlement expected = resolve();
        assertThrows(IllegalStateException.class, () -> service().requireInCurrentTransaction(expected, context));
        assertThrows(IllegalStateException.class, () -> f.f.tx.readOnly(() -> {
            service().requireInCurrentTransaction(expected, context);
            return null;
        }));
    }

    <T> T repeatableRead(java.util.function.Supplier<T> work) {
        org.springframework.transaction.support.TransactionTemplate outer = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(f.f.source));
        outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        return outer.execute(status -> f.f.tx.required(work));
    }

    @Test
    void staleRepeatableReadSnapshotCannotBypassActivation() {
        Assumptions.assumeTrue(f.f.mysql != null, "MySQL current-read phantom semantics; H2 RR permits this phantom");
        AiEntitlement expected = resolve();
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            assertThrows(RuntimeException.class, () -> repeatableRead(() -> {
                assertTrue(scopes.find(f.scope).isEmpty());
                await(pool.submit(this::migrate));
                assertTrue(scopes.find(f.scope).isEmpty());
                service().requireInCurrentTransaction(expected, context);
                return null;
            }));
        }
        assertNotNull(resolve().modelAccess());
    }

    @Test
    void staleRepeatableReadSnapshotCannotBypassRevoke() {
        migrate();
        AiEntitlement expected = resolve();
        try (ExecutorService pool = Executors.newSingleThreadExecutor()) {
            assertThrows(RuntimeException.class, () -> repeatableRead(() -> {
                long old = f.stores.grants().findAccess(model.resource()).orElseThrow().aclVersion();
                await(pool.submit(this::revoke));
                assertEquals(old, f.stores.grants().findAccess(model.resource()).orElseThrow().aclVersion());
                service().requireInCurrentTransaction(expected, context);
                return null;
            }));
        }
        assertTrue(discover().isEmpty());
    }

    @Test
    void currentExecutionGateHoldsRevokeUntilOriginalCommit() throws Exception {
        migrate();
        AiEntitlement expected = resolve();
        CountDownLatch held = new CountDownLatch(1), finish = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<?> execution = pool.submit(() -> f.f.tx.required(() -> {
                service().requireInCurrentTransaction(expected, context);
                held.countDown();
                try {
                    assertTrue(finish.await(10, TimeUnit.SECONDS));
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return null;
            }));
            assertTrue(held.await(10, TimeUnit.SECONDS));
            Future<?> revoke = pool.submit(this::revoke);
            try {
                assertThrows(TimeoutException.class, () -> revoke.get(200, TimeUnit.MILLISECONDS));
            } finally {
                finish.countDown();
            }
            execution.get(10, TimeUnit.SECONDS);
            revoke.get(10, TimeUnit.SECONDS);
        }
        assertTrue(discover().isEmpty());
    }
}
