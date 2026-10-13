package com.arte.app.platform.persistence;

import com.arte.app.platform.identity.*;
import com.arte.app.service.platform.*;
import com.arte.core.application.workspace.DefaultWorkspacePolicies;
import com.arte.core.application.workspace.DefaultWorkspaceServices;
import com.arte.core.enums.platform.CommandDeliveryEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.scheduler.Schedulers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class PlatformPersistenceIntegrationTest {
    DriverManagerDataSource source;
    JdbcTemplate jdbc;
    JdbcPublicTransactionRunner tx;
    TenantStoreServiceImpl tenants;
    WorkspaceStoreServiceImpl workspaces;
    TenantMembershipStoreServiceImpl tenantMembers;
    WorkspaceMembershipStoreServiceImpl workspaceMembers;
    PreferenceStoreServiceImpl preferences;
    PolicyStoreServiceImpl policies;
    CommandReceiptStoreServiceImpl receipts;
    PublicOutboxStoreServiceImpl outbox;
    AuditSinkServiceImpl audit;
    WorkspaceAccessFactsStoreServiceImpl facts;
    DefaultWorkspaceServices services;
    TrustedActor actor;
    PlatformJson json;
    String database;
    String mysql = System.getProperty("platform.test.mysql");

    static class Cursors implements WorkspaceCursorStore {
        final Map<String, Cursor> values = new ConcurrentHashMap<>();

        public String save(Cursor cursor) {
            String token = UUID.randomUUID().toString();
            values.put(token, cursor);
            return token;
        }

        public Cursor load(String token) {
            return Optional.ofNullable(values.get(token))
                    .orElseThrow(() -> new IllegalArgumentException("Invalid cursor"));
        }
    }

    @BeforeEach
    void setup() throws Exception {
        database = "platform_test_" + UUID.randomUUID().toString().replace("-", "");
        if (mysql == null)
            source =
                    new DriverManagerDataSource(
                            "jdbc:h2:mem:"
                                    + database
                                    + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000",
                            "sa",
                            "");
        else {
            var admin =
                    new JdbcTemplate(new DriverManagerDataSource(mysql, "root", "platform-test"));
            admin.execute(
                    "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
            source =
                    new DriverManagerDataSource(
                            mysql.replace("/mysql?", "/" + database + "?"),
                            "root",
                            "platform-test");
        }
        jdbc = new JdbcTemplate(source);
        jdbc.execute(
                "CREATE TABLE arte_rbac_user(id BIGINT PRIMARY KEY,user_name VARCHAR(64)"
                        + " UNIQUE,status INT,create_time DATETIME(6) NOT NULL)");
        for (String script :
                List.of(
                        "arte-platform-identity-ddl-mysql.sql",
                        "arte-platform-workspace-ddl-mysql.sql",
                        "arte-platform-personal-policy-template-ddl-mysql.sql")) {
            String sql = Files.readString(Path.of("scripts", script));
            if (mysql == null)
                sql =
                        sql.replaceAll("(?i)CHARACTER SET [a-z0-9_]+ COLLATE [a-z0-9_]+", "")
                                .replaceAll("(?i)ENGINE=InnoDB", "")
                                .replaceAll("(?im)^SET\\s+[^;]*;", "");
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(
                        connection,
                        new ByteArrayResource(
                                sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
        jdbc.update(
                "INSERT INTO arte_rbac_user VALUES(1,'alice',1,?)",
                java.sql.Timestamp.valueOf("2026-10-10 10:00:00"));
        var accounts =
                new RbacAccountReader(
                        source, new RbacAccountMapperConfiguration().identityUserMapper(source));
        var identities = new JdbcPrincipalIdentityStore(source, accounts);
        var provider =
                new RbacIdentityProvider(
                        accounts,
                        identities,
                        new TransactionTemplate(new DataSourceTransactionManager(source)),
                        new PlatformIdentityProperties(true, 0L, List.of()),
                        List::of,
                        Clock.systemUTC());
        actor =
                new TrustedActor(
                        provider.resolveAuthenticatedName("alice").orElseThrow(), Instant.now());
        tx = new JdbcPublicTransactionRunner(source, new DataSourceTransactionManager(source), 15);
        json = new PlatformJson();
        var factory = new PlatformServiceTestFactory(source, tx, json, new Cursors());
        tenants = factory.tenants();
        workspaces = factory.workspaces();
        tenantMembers = factory.tenantMembers();
        workspaceMembers = factory.workspaceMembers();
        preferences = factory.preferences();
        policies = factory.policies();
        receipts = factory.receipts();
        outbox = factory.outbox();
        audit = factory.audit();
        facts = factory.facts();
        services = services(provider, outbox, audit);
    }

    DefaultWorkspaceServices services(
            com.arte.core.spi.platform.IdentityProvider provider,
            com.arte.core.spi.platform.PublicOutboxStore events,
            com.arte.core.spi.platform.AuditSink audits) {
        return new DefaultWorkspaceServices(
                provider,
                tx,
                tenants,
                workspaces,
                tenantMembers,
                workspaceMembers,
                policies,
                preferences,
                receipts,
                events,
                audits,
                () -> UUID.randomUUID().toString(),
                Clock.systemUTC(),
                new DefaultWorkspacePolicies(new PlatformServiceTestFactory(source, tx, json, new Cursors()).templates()),
                facts);
    }

    @AfterEach
    void cleanup() {
        if (mysql != null)
            new JdbcTemplate(new DriverManagerDataSource(mysql, "root", "platform-test"))
                    .execute("DROP DATABASE " + database);
    }

    CommandMeta meta(String key) {
        return new CommandMeta(key, "test-trace");
    }

    CommandReceipt<WorkspaceViews.PersonalWorkspaceView> initialize() {
        return services.personal().initialize(actor, meta("init"));
    }

    void gates(Scope scope) {
        preferences.lockSubject(actor.principal().subjectId());
        tenants.lock(scope.tenantId()).orElseThrow();
        workspaces.lock(scope).orElseThrow();
    }

    long count(String suffix) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_" + suffix, Long.class);
    }

    @Test
    void realDatabaseInitializationReplayAndOneAssociationAcrossKeys() {
        var first = initialize();
        var replay = initialize();
        var other = services.personal().initialize(actor, meta("different"));
        assertEquals(first.operationId(), replay.operationId());
        assertEquals(CommandDeliveryEnum.REPLAYED, replay.delivery());
        assertEquals(first.value().workspace().scope(), other.value().workspace().scope());
        assertEquals(1, count("tenant"));
        assertEquals(1, count("workspace"));
        assertEquals(2, count("command"));
        assertEquals(1, count("outbox"));
        assertEquals(1, count("audit"));
        assertEquals(
                first, receipts.findByOperationId(first.operationId()).orElseThrow().receipt());
    }

    DefaultWorkspaceServices independentInstance() {
        var ds =
                new DriverManagerDataSource(
                        source.getUrl(), source.getUsername(), source.getPassword());
        var accounts =
                new RbacAccountReader(
                        ds, new RbacAccountMapperConfiguration().identityUserMapper(ds));
        var provider =
                new RbacIdentityProvider(
                        accounts,
                        new JdbcPrincipalIdentityStore(ds, accounts),
                        new TransactionTemplate(new DataSourceTransactionManager(ds)),
                        new PlatformIdentityProperties(true, 0L, List.of()),
                        List::of,
                        Clock.systemUTC());
        var runner = new JdbcPublicTransactionRunner(ds, new DataSourceTransactionManager(ds), 15);
        var factory = new PlatformServiceTestFactory(ds, runner, json, new Cursors());
        var tenantStore = factory.tenants();
        var workspaceStore = factory.workspaces();
        var tenantMemberStore = factory.tenantMembers();
        var workspaceMemberStore = factory.workspaceMembers();
        var preferenceStore = factory.preferences();
        var policyStore = factory.policies();
        return new DefaultWorkspaceServices(
                provider,
                runner,
                tenantStore,
                workspaceStore,
                tenantMemberStore,
                workspaceMemberStore,
                policyStore,
                preferenceStore,
                factory.receipts(),
                factory.outbox(),
                factory.audit(),
                () -> UUID.randomUUID().toString(),
                Clock.systemUTC(),
                new DefaultWorkspacePolicies(factory.templates()),
                factory.facts());
    }

    @Test
    void concurrentInstancesProduceExactlyOnePersonalSpace() throws Exception {
        var secondInstance = independentInstance();
        try (var pool = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<Future<Scope>>();
            for (int i = 0; i < 12; i++) {
                String key = "init-" + i;
                var instance = i % 2 == 0 ? services : secondInstance;
                jobs.add(
                        pool.submit(
                                () ->
                                        instance.personal()
                                                .initialize(actor, meta(key))
                                                .value()
                                                .workspace()
                                                .scope()));
            }
            Scope winner = jobs.getFirst().get(30, TimeUnit.SECONDS);
            for (var job : jobs) assertEquals(winner, job.get(30, TimeUnit.SECONDS));
        }
        assertEquals(1, count("tenant"));
        assertEquals(1, count("workspace"));
        assertEquals(12, count("command"));
        assertEquals(1, count("outbox"));
    }

    @Test
    void outerRollbackIncludesAssociationsReceiptsEventsAndAudit() {
        assertThrows(
                org.springframework.transaction.UnexpectedRollbackException.class,
                () ->
                        tx.required(
                                () -> {
                                    initialize();
                                    tx.markRollbackOnly();
                                    return null;
                                }));
        for (String table :
                List.of(
                        "tenant",
                        "workspace",
                        "tenant_member",
                        "workspace_member",
                        "tenant_policy",
                        "workspace_policy",
                        "workspace_preference",
                        "command",
                        "outbox",
                        "audit"))
            assertEquals(0, count(table), table);
        assertEquals(1, count("principal_identity"));
    }

    @Test
    void auditFailureRollsBackWholeCommand() {
        var accounts =
                new RbacAccountReader(
                        source, new RbacAccountMapperConfiguration().identityUserMapper(source));
        var provider =
                new RbacIdentityProvider(
                        accounts,
                        new JdbcPrincipalIdentityStore(source, accounts),
                        new TransactionTemplate(new DataSourceTransactionManager(source)),
                        new PlatformIdentityProperties(true, 0L, List.of()),
                        List::of,
                        Clock.systemUTC());
        var failing =
                services(
                        provider,
                        outbox,
                        e -> {
                            throw new IllegalStateException("Injected audit failure");
                        });
        assertThrows(
                IllegalStateException.class,
                () -> failing.personal().initialize(actor, meta("fail")));
        assertEquals(0, count("tenant"));
        assertEquals(0, count("outbox"));
        assertEquals(0, count("command"));
    }

    @Test
    void policyAuditFailureRollsBackPersistedPoliciesParentVersionsAndEpochs() {
        var scope = initialize().value().workspace().scope();
        var accounts = new RbacAccountReader(source, new RbacAccountMapperConfiguration().identityUserMapper(source));
        var provider = new RbacIdentityProvider(accounts, new JdbcPrincipalIdentityStore(source, accounts),
                new TransactionTemplate(new DataSourceTransactionManager(source)),
                new PlatformIdentityProperties(true, 0L, List.of()), List::of, Clock.systemUTC());
        var failing = services(provider, outbox, e -> {
            throw new IllegalStateException("Injected policy audit failure");
        });
        var tenant = policies.findTenantPolicy(scope.tenantId()).orElseThrow();
        var workspace = policies.findWorkspacePolicy(scope).orElseThrow();
        assertThrows(IllegalStateException.class, () -> failing.policies().updateTenantPolicy(actor,
                new com.arte.core.pojo.workspace.TenantCommands.UpdateTenantPolicy(meta("failed-tenant-policy"), scope.tenantId(),
                        0, 0, tenant.rules(), true)));
        assertThrows(IllegalStateException.class, () -> failing.policies().updateWorkspacePolicy(actor,
                new WorkspaceCommands.UpdateWorkspacePolicy(meta("failed-space-policy"), scope, 0, 0, workspace.rules())));
        assertEquals(tenant, policies.findTenantPolicy(scope.tenantId()).orElseThrow());
        assertEquals(workspace, policies.findWorkspacePolicy(scope).orElseThrow());
        assertEquals(0, tenants.find(scope.tenantId()).orElseThrow().version());
        assertEquals(0, tenants.find(scope.tenantId()).orElseThrow().authEpoch());
        assertEquals(0, workspaces.find(scope).orElseThrow().version());
        assertEquals(0, workspaces.find(scope).orElseThrow().authEpoch());
        assertEquals(1, count("outbox"));
        assertEquals(1, count("audit"));
        assertEquals(1, count("command"));
    }

    @Test
    void legacyPolicyWithNoTemplateProvenanceIsNotRebasedByInitializationOrGovernance() {
        var scope = initialize().value().workspace().scope();
        jdbc.update("UPDATE arte_platform_tenant_policy SET personal_template_version=NULL WHERE tenant_id=?", scope.tenantId());
        assertNull(policies.findTenantPolicy(scope.tenantId()).orElseThrow().personalTemplateVersion());
        services.personal().initialize(actor, meta("legacy-existing"));
        var old = policies.findTenantPolicy(scope.tenantId()).orElseThrow();
        var updated = services.policies().updateTenantPolicy(actor,
                new com.arte.core.pojo.workspace.TenantCommands.UpdateTenantPolicy(meta("legacy-policy-update"), scope.tenantId(),
                        0, 0, old.rules(), old.membersMayCreateWorkspace()));
        assertNull(updated.value().personalTemplateVersion());
        assertNull(policies.findTenantPolicy(scope.tenantId()).orElseThrow().personalTemplateVersion());
        assertEquals(1, updated.value().version());
    }

    @Test
    void versionUpdatesAndImmutableCreationMetadata() {
        var scope = initialize().value().workspace().scope();
        var before = workspaces.find(scope).orElseThrow();
        var renamed =
                services.workspaces()
                        .rename(
                                actor,
                                new WorkspaceCommands.RenameWorkspace(
                                        meta("rename"), scope, 0, "Renamed", "new"));
        assertEquals(1, renamed.resultVersion());
        assertEquals(before.createdAt(), workspaces.find(scope).orElseThrow().createdAt());
        assertThrows(
                PlatformException.class,
                () ->
                        services.workspaces()
                                .rename(
                                        actor,
                                        new WorkspaceCommands.RenameWorkspace(
                                                meta("stale"), scope, 0, "Lost", "")));
        assertThrows(
                PlatformException.class,
                () ->
                        services.workspaces()
                                .rename(
                                        actor,
                                        new WorkspaceCommands.RenameWorkspace(
                                                meta("rename"), scope, 0, "Different", "")));
        tx.required(
                () -> {
                    gates(scope);
                    var now = workspaces.find(scope).orElseThrow();
                    assertFalse(
                            workspaces.replace(
                                    new Workspace(
                                            scope,
                                            "bad",
                                            "",
                                            now.state(),
                                            now.createdBy(),
                                            1,
                                            now.authEpoch(),
                                            now.createdAt(),
                                            now.updatedAt()),
                                    0));
                    assertFalse(
                            workspaces.replace(
                                    new Workspace(
                                            scope,
                                            "bad",
                                            "",
                                            now.state(),
                                            now.createdBy(),
                                            2,
                                            now.authEpoch(),
                                            now.createdAt().plusNanos(1),
                                            now.updatedAt().plusNanos(1)),
                                    1));
                    return null;
                });
        assertEquals("Renamed", workspaces.find(scope).orElseThrow().name());
    }

    @Test
    void membershipCasRejectsStaleVersionsAndOlderGeneration() {
        var scope = initialize().value().workspace().scope();
        var subject = actor.principal().subjectId();
        tx.required(() -> {
            gates(scope);
            var tenantMember = tenantMembers.lock(scope.tenantId(), subject).orElseThrow();
            var nextTenant = new TenantMembership(tenantMember.id(), tenantMember.tenantId(), subject,
                    tenantMember.role(), tenantMember.state(), 1, 1);
            assertTrue(tenantMembers.replace(nextTenant, 0));
            assertFalse(tenantMembers.replace(nextTenant, 0));
            assertFalse(tenantMembers.replace(new TenantMembership(tenantMember.id(), tenantMember.tenantId(), subject,
                    tenantMember.role(), tenantMember.state(), 0, 2), 1));
            var workspaceMember = workspaceMembers.lock(scope, subject).orElseThrow();
            var nextWorkspace = new WorkspaceMembership(workspaceMember.id(), scope, subject,
                    workspaceMember.role(), workspaceMember.state(), 1, 1);
            assertTrue(workspaceMembers.replace(nextWorkspace, 0));
            assertFalse(workspaceMembers.replace(nextWorkspace, 0));
            assertFalse(workspaceMembers.replace(new WorkspaceMembership(workspaceMember.id(), scope, subject,
                    workspaceMember.role(), workspaceMember.state(), 0, 2), 1));
            return null;
        });
        assertEquals(1, tenantMembers.find(scope.tenantId(), subject).orElseThrow().generation());
        assertEquals(1, workspaceMembers.find(scope, subject).orElseThrow().generation());
    }

    @Test
    void tenantAndPolicyCasPreserveEpochAssociationAndVersions() {
        var scope = initialize().value().workspace().scope();
        tx.required(() -> {
            gates(scope);
            var tenant = tenants.lock(scope.tenantId()).orElseThrow();
            var changed = new Tenant(tenant.id(), "renamed tenant", tenant.type(), tenant.personalSubjectId(),
                    tenant.state(), tenant.createdBy(), 1, 1, tenant.createdAt(), tenant.updatedAt().plusNanos(1));
            assertTrue(tenants.replace(changed, 0));
            assertFalse(tenants.replace(changed, 0));
            assertFalse(tenants.replace(new Tenant(tenant.id(), tenant.name(), tenant.type(), tenant.personalSubjectId(),
                    tenant.state(), tenant.createdBy(), 2, 0, tenant.createdAt(), tenant.updatedAt().plusNanos(2)), 1));
            assertFalse(tenants.replace(new Tenant(tenant.id(), tenant.name(), tenant.type(), "different-subject",
                    tenant.state(), tenant.createdBy(), 2, 1, tenant.createdAt(), tenant.updatedAt().plusNanos(2)), 1));
            var tp = policies.findCurrentTenantPolicy(scope.tenantId()).orElseThrow();
            var nextTenantPolicy = new TenantPolicy(tp.tenantId(), tp.rules(), tp.membersMayCreateWorkspace(),
                    tp.policySchemaVersion(), tp.rolePresetVersion(), 1);
            assertTrue(policies.replaceTenantPolicy(nextTenantPolicy, 0));
            assertFalse(policies.replaceTenantPolicy(nextTenantPolicy, 0));
            var wp = policies.findCurrentWorkspacePolicy(scope).orElseThrow();
            var nextWorkspacePolicy = new WorkspacePolicy(scope, wp.rules(), wp.policySchemaVersion(), wp.rolePresetVersion(), 1);
            assertTrue(policies.replaceWorkspacePolicy(nextWorkspacePolicy, 0));
            assertFalse(policies.replaceWorkspacePolicy(nextWorkspacePolicy, 0));
            return null;
        });
        assertEquals(1, policies.findTenantPolicy(scope.tenantId()).orElseThrow().version());
        assertEquals(1, policies.findWorkspacePolicy(scope).orElseThrow().version());
        assertEquals(1, tenants.find(scope.tenantId()).orElseThrow().authEpoch());
    }

    @Test
    void preferenceCannotChangeSystemDefaultAndRoundTripsFavorites() {
        var scope = initialize().value().workspace().scope();
        var next =
                services.workspaces()
                        .create(
                                actor,
                                new WorkspaceCommands.CreateWorkspace(
                                        meta("create"), scope.tenantId(), "Another", ""))
                        .value()
                        .scope();
        tx.required(
                () -> {
                    gates(scope);
                    var old = preferences.findCurrent(actor.principal().subjectId()).orElseThrow();
                    assertTrue(
                            preferences.replace(
                                    new WorkspacePreference(
                                            old.subjectId(), scope, next, next, Set.of(next), 1),
                                    0));
                    assertFalse(
                            preferences.replace(
                                    new WorkspacePreference(
                                            old.subjectId(), next, null, null, Set.of(), 2),
                                    1));
                    return null;
                });
        var actual = preferences.find(actor.principal().subjectId()).orElseThrow();
        assertEquals(scope, actual.defaultScope());
        assertEquals(Set.of(next), actual.favorites());
    }

    @Test
    void bulkFactsRespectFullScopeAndTargetSubject() {
        var scope = initialize().value().workspace().scope();
        var bad = new Scope("other-tenant", scope.workspaceId());
        var result =
                facts.read(
                        actor.principal().subjectId(),
                        Set.of(scope.tenantId(), "other-tenant"),
                        Set.of(scope, bad),
                        true);
        assertEquals(Set.of(scope), result.workspaces().keySet());
        assertEquals(Set.of(scope), result.workspaceMembers().keySet());
        assertEquals(1, result.defaultPreferences().size());
        assertTrue(
                facts.read("other-user", Set.of(scope.tenantId()), Set.of(scope), false)
                        .workspaceMembers()
                        .isEmpty());
        assertThrows(
                IllegalArgumentException.class,
                () -> facts.read("other-user", Set.of(), Set.of(scope), false));
    }

    @Test
    void keysetScanSurvivesBoundaryDeletionRenameAndNanosecondTimes() {
        var scope = initialize().value().workspace().scope();
        var first =
                services.workspaces()
                        .create(
                                actor,
                                new WorkspaceCommands.CreateWorkspace(
                                        meta("one"), scope.tenantId(), "One", ""))
                        .value();
        var second =
                services.workspaces()
                        .create(
                                actor,
                                new WorkspaceCommands.CreateWorkspace(
                                        meta("two"), scope.tenantId(), "Two", ""))
                        .value();
        var page = workspaces.scanMembershipCandidates(actor.principal().subjectId(), null, 1);
        assertEquals(second.scope(), page.records().getFirst().scope());
        assertNotNull(page.nextCursor());
        services.workspaces()
                .rename(
                        actor,
                        new WorkspaceCommands.RenameWorkspace(
                                meta("rename-one"), first.scope(), 0, "Changed", ""));
        jdbc.update(
                "DELETE FROM arte_platform_workspace_member WHERE workspace_id=?",
                second.scope().workspaceId());
        var next =
                workspaces.scanMembershipCandidates(
                        actor.principal().subjectId(), page.nextCursor(), 1);
        assertEquals(first.scope(), next.records().getFirst().scope());
        assertThrows(
                IllegalArgumentException.class,
                () -> workspaces.scanMembershipCandidates("other-user", page.nextCursor(), 1));
        assertEquals(
                scope,
                workspaces
                        .scanMembershipCandidates(
                                actor.principal().subjectId(), next.nextCursor(), 1)
                        .records()
                        .getFirst()
                        .scope());
    }

    @Test
    void storageGatesRejectUnsafeTransactionAndLockUsage() {
        assertThrows(
                IllegalStateException.class,
                () -> preferences.lockSubject(actor.principal().subjectId()));
        assertThrows(IllegalStateException.class, () -> tx.required(() -> tenants.lock("missing")));
        assertThrows(
                IllegalStateException.class,
                () -> tx.required(() -> workspaces.lock(new Scope("missing", "missing"))));
        assertThrows(
                IllegalStateException.class,
                () ->
                        receipts.findCurrent(
                                new CommandIdentity(
                                        actor.principal().subjectId(),
                                        new CommandScope(null, null),
                                        "personal:initialize",
                                        "init")));
    }

    @Test
    void transactionResourcesShareAcrossRunnersAndSuspendWithRequiresNew() {
        var other =
                new JdbcPublicTransactionRunner(
                        source, new DataSourceTransactionManager(source), 15);
        Object key = new Object();
        var nested = new TransactionTemplate(new DataSourceTransactionManager(source));
        nested.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        tx.required(
                () -> {
                    Object original = tx.resource(key, Object::new);
                    assertSame(original, other.required(() -> other.resource(key, Object::new)));
                    nested.execute(
                            status -> {
                                assertNotSame(original, tx.resource(key, Object::new));
                                return null;
                            });
                    assertSame(original, tx.resource(key, Object::new));
                    return null;
                });
        assertFalse(tx.isTransactionActive());
        assertFalse(TransactionSynchronizationManager.hasResource(source));
    }

    @Test
    void caughtNestedFailureStillPreventsOuterCommit() {
        assertThrows(
                org.springframework.transaction.UnexpectedRollbackException.class,
                () ->
                        tx.required(
                                () -> {
                                    try {
                                        tx.required(
                                                () -> {
                                                    initialize();
                                                    throw new IllegalStateException("failure");
                                                });
                                    } catch (IllegalStateException expected) {
                                    }
                                    return null;
                                }));
        assertEquals(0, count("tenant"));
    }

    @Test
    void outboxDuplicatesConflictsLeaseFencingAndRetries() {
        var scope = initialize().value().workspace().scope();
        var event =
                new PublicEvent(
                        "fixed-event",
                        new CommandScope(scope.tenantId(), scope.workspaceId()),
                        "workspace:changed",
                        1,
                        0,
                        Instant.now(),
                        Map.of("version", "0"));
        tx.required(
                () -> {
                    outbox.append(event);
                    outbox.append(event);
                    return null;
                });
        assertEquals(2, count("outbox"));
        assertThrows(
                IllegalStateException.class,
                () ->
                        tx.required(
                                () -> {
                                    outbox.append(
                                            new PublicEvent(
                                                    event.id(),
                                                    event.scope(),
                                                    event.eventType(),
                                                    1,
                                                    1,
                                                    event.occurredAt(),
                                                    Map.of()));
                                    return null;
                                }));
        var first = tx.required(() -> outbox.claim(100, Duration.ofSeconds(5)));
        assertEquals(2, first.size());
        assertTrue(tx.required(() -> outbox.claim(100, Duration.ofSeconds(5))).isEmpty());
        var d = first.getFirst();
        assertFalse(
                tx.required(
                        () ->
                                outbox.acknowledge(
                                        new com.arte.app.api.platform.OutboxDeliveryService.Delivery(
                                                d.event(), "wrong-token"))));
        assertTrue(tx.required(() -> outbox.retry(d, Duration.ZERO)));
        var second = tx.required(() -> outbox.claim(100, Duration.ofSeconds(5)));
        assertEquals(1, second.size());
        assertFalse(tx.required(() -> outbox.acknowledge(d)));
        assertTrue(tx.required(() -> outbox.acknowledge(second.getFirst())));
    }

    @Test
    void nanosecondScanKeyAndItsForeignKeyRemainExact() {
        var scope = initialize().value().workspace().scope();
        long sec = Instant.now().getEpochSecond() + 10;
        var low = new Scope(scope.tenantId(), "nano-low");
        var high = new Scope(scope.tenantId(), "nano-high");
        tx.required(
                () -> {
                    gates(scope);
                    for (var s : List.of(low, high)) {
                        var created =
                                Instant.ofEpochSecond(sec, s.equals(low) ? 123456788 : 123456789);
                        assertTrue(
                                workspaces.insert(
                                        new Workspace(
                                                s,
                                                s.workspaceId(),
                                                "",
                                                com.arte.core.enums.workspace.WorkspaceStateEnum
                                                        .ACTIVE,
                                                actor.principal().subjectId(),
                                                0,
                                                0,
                                                created,
                                                created)));
                        assertTrue(
                                workspaceMembers.insert(
                                        new WorkspaceMembership(
                                                UUID.randomUUID().toString(),
                                                s,
                                                actor.principal().subjectId(),
                                                com.arte.core.enums.workspace.WorkspaceRoleEnum
                                                        .OWNER,
                                                com.arte.core.enums.workspace.MembershipStateEnum
                                                        .ACTIVE,
                                                0,
                                                0)));
                    }
                    return null;
                });
        assertEquals(123456789, workspaces.find(high).orElseThrow().createdAt().getNano());
        var page = workspaces.scanMembershipCandidates(actor.principal().subjectId(), null, 1);
        assertEquals(high, page.records().getFirst().scope());
        assertEquals(
                low,
                workspaces
                        .scanMembershipCandidates(
                                actor.principal().subjectId(), page.nextCursor(), 1)
                        .records()
                        .getFirst()
                        .scope());
        assertThrows(
                org.springframework.dao.DataIntegrityViolationException.class,
                () ->
                        jdbc.update(
                                "UPDATE arte_platform_workspace_member SET workspace_created_nano=0"
                                        + " WHERE workspace_id=?",
                                high.workspaceId()));
    }

    @Test
    void timestampsRetainNanosecondsAndPoliciesRetainTypeKeys() {
        var scope = initialize().value().workspace().scope();
        var value = workspaces.find(scope).orElseThrow();
        assertEquals(
                value,
                tx.required(
                        () -> {
                            gates(scope);
                            return workspaces.lock(scope).orElseThrow();
                        }));
        assertEquals(
                policies.findWorkspacePolicy(scope).orElseThrow(),
                json.read(
                        json.write(policies.findWorkspacePolicy(scope).orElseThrow()),
                        WorkspacePolicy.class));
    }

    @Test
    void hundredScopeFactsUseSevenSetQueries() throws Exception {
        var scope = initialize().value().workspace().scope();
        var ranges = new HashSet<Scope>();
        ranges.add(scope);
        for (int i = 1; i < 100; i++) ranges.add(new Scope(scope.tenantId(), "absent-" + i));
        var selects = new java.util.concurrent.atomic.AtomicInteger();
        var counted =
                new org.springframework.jdbc.datasource.DelegatingDataSource(source) {
                    @Override
                    public java.sql.Connection getConnection() throws java.sql.SQLException {
                        var connection = super.getConnection();
                        return (java.sql.Connection)
                                java.lang.reflect.Proxy.newProxyInstance(
                                        getClass().getClassLoader(),
                                        new Class[]{java.sql.Connection.class},
                                        (proxy, method, args) -> {
                                            if (method.getName().equals("prepareStatement")
                                                    && args[0].toString().startsWith("SELECT"))
                                                selects.incrementAndGet();
                                            try {
                                                return method.invoke(connection, args);
                                            } catch (
                                                    java.lang.reflect.InvocationTargetException
                                                            failure) {
                                                throw failure.getCause();
                                            }
                                        });
                    }
                };
        var batch = new PlatformServiceTestFactory(counted, tx, json, new Cursors()).facts();
        assertEquals(
                1,
                batch.read(actor.principal().subjectId(), Set.of(scope.tenantId()), ranges, true)
                        .workspaces()
                        .size());
        assertEquals(7, selects.get());
        ranges.add(new Scope(scope.tenantId(), "overflow"));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        batch.read(
                                actor.principal().subjectId(),
                                Set.of(scope.tenantId()),
                                ranges,
                                true));
    }

    @Test
    void lockedCurrentReadSeesCommitAfterEarlierRepeatableReadSnapshot() throws Exception {
        var scope = initialize().value().workspace().scope();
        tx.required(
                () -> {
                    var old = workspaces.find(scope).orElseThrow();
                    try (var pool = Executors.newSingleThreadExecutor()) {
                        pool.submit(
                                        () ->
                                                independentInstance()
                                                        .workspaces()
                                                        .rename(
                                                                actor,
                                                                new WorkspaceCommands
                                                                        .RenameWorkspace(
                                                                        meta("external"),
                                                                        scope,
                                                                        0,
                                                                        "Committed elsewhere",
                                                                        "")))
                                .get(10, TimeUnit.SECONDS);
                    } catch (Exception failure) {
                        throw new IllegalStateException(failure);
                    }
                    gates(scope);
                    assertEquals(old.version() + 1, workspaces.lock(scope).orElseThrow().version());
                    return null;
                });
    }

    @Test
    void caughtMandatoryAuditAndOutboxFailuresCannotCommit() {
        var scope = initialize().value().workspace().scope();
        var entry =
                new AuditEntry(
                        "fixed-audit",
                        actor.principal().subjectId(),
                        new CommandScope(scope.tenantId(), scope.workspaceId()),
                        "workspace:changed",
                        "test-operation",
                        "",
                        Instant.now());
        assertThrows(
                org.springframework.transaction.UnexpectedRollbackException.class,
                () ->
                        tx.required(
                                () -> {
                                    audit.append(entry);
                                    try {
                                        audit.append(entry);
                                    } catch (
                                            org.springframework.dao.DuplicateKeyException
                                                    expected) {
                                    }
                                    return null;
                                }));
        assertEquals(1, count("audit"));
        var event =
                new PublicEvent(
                        "conflict-event",
                        entry.scope(),
                        "workspace:changed",
                        1,
                        0,
                        Instant.now(),
                        Map.of());
        assertThrows(
                org.springframework.transaction.UnexpectedRollbackException.class,
                () ->
                        tx.required(
                                () -> {
                                    outbox.append(event);
                                    try {
                                        outbox.append(
                                                new PublicEvent(
                                                        event.id(),
                                                        event.scope(),
                                                        event.eventType(),
                                                        1,
                                                        1,
                                                        event.occurredAt(),
                                                        Map.of()));
                                    } catch (IllegalStateException expected) {
                                    }
                                    return null;
                                }));
        assertEquals(1, count("outbox"));
    }

    @Test
    void responseSchemaRejectsUnregisteredAndMalformedTypes() {
        assertThrows(IllegalStateException.class, () -> json.resultType("java:class"));
        assertThrows(
                Exception.class,
                () ->
                        json.read(
                                "{\"tenantId\":\"t\",\"workspaceId\":\"s\",\"unexpected\":true}",
                                Scope.class));
        var initialized = initialize();
        var stored = receipts.findByOperationId(initialized.operationId()).orElseThrow();
        assertEquals(stored, json.receipt(json.write(stored)));
    }

    @Test
    void reactiveBoundaryIsLazyAndEntireTransactionUsesOneWorkerThread() {
        var scheduler = Schedulers.newBoundedElastic(2, 8, "test-platform");
        try {
            var executor = new PlatformPersistenceExecutor(scheduler, tx);
            var called = new java.util.concurrent.atomic.AtomicBoolean();
            var mono =
                    executor.inTransaction(
                            () -> {
                                called.set(true);
                                assertTrue(tx.isTransactionActive());
                                assertTrue(
                                        Thread.currentThread()
                                                .getName()
                                                .startsWith("test-platform"));
                                return initialize().operationId();
                            });
            assertFalse(called.get());
            assertNotNull(mono.block(Duration.ofSeconds(10)));
            assertTrue(called.get());
            assertFalse(tx.isTransactionActive());
        } finally {
            scheduler.dispose();
        }
    }
}
