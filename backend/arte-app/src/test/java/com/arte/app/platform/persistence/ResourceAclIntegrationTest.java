package com.arte.app.platform.persistence;

import com.arte.app.service.platform.ResourceGrantStoreServiceImpl;
import com.arte.core.application.workspace.DefaultWorkspacePolicies;
import com.arte.core.application.workspace.DefaultWorkspaceServices;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.enums.access.*;
import com.arte.core.enums.platform.CommandDeliveryEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.*;
import com.arte.core.pojo.platform.*;
import com.arte.core.pojo.workspace.*;
import com.arte.core.spi.access.DomainAccessConditionProvider;
import com.arte.core.spi.access.ResourceDescriptorProvider;
import com.arte.core.spi.platform.AuditSink;
import com.arte.core.spi.platform.IdentityProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static com.arte.core.constant.platform.PlatformActions.*;
import static com.arte.core.constant.platform.PlatformResourceTypes.MODEL;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真正的 Mapper/DDL/事务：领域适配仅用于测试，不注册生产模型或修改现有 AI 来源。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/30 17:50 ✾
 */
class ResourceAclIntegrationTest {

    JdbcTemplate jdbc;
    DriverManagerDataSource source;
    String database;
    final String mysql = System.getProperty("platform.acl.test.mysql");
    JdbcPublicTransactionRunner tx;
    PlatformServiceTestFactory stores;
    ResourceGrantStoreServiceImpl grants;
    DefaultWorkspaceServices services;
    Scope scope;
    ResourceRef resource;
    TrustedActor alice, bob, carol;
    final Map<String, Principal> principals = new ConcurrentHashMap<>();
    final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-30T00:00:00Z"));
    final Clock clock = new Clock() {
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now.get();
        }
    };
    final IdentityProvider identity = new IdentityProvider() {
        public Optional<Principal> resolveAuthenticatedName(String name) {
            return findBySubjectId(name);
        }

        public Optional<Principal> findBySubjectId(String id) {
            return Optional.ofNullable(principals.get(id));
        }

        public Optional<Principal> findCurrentBySubjectId(String id) {
            tx.requireActive();
            return findBySubjectId(id);
        }
    };
    final ResourceDescriptorProvider descriptor = new ResourceDescriptorProvider() {
        public boolean supports(ResourceType type) {
            return type.equals(MODEL);
        }

        public Optional<ResourceDescriptor> find(ResourceRef ref) {
            return readDescriptor(ref, false);
        }

        public Optional<ResourceDescriptor> findCurrent(ResourceRef ref) {
            tx.requireWritable();
            return readDescriptor(ref, true);
        }
    };
    final DomainAccessConditionProvider condition = new DomainAccessConditionProvider() {
        public boolean supports(ResourceType type, Action action) {
            return type.equals(MODEL);
        }

        public AccessReasonEnum evaluate(TrustedActor actor, AccessRequest r, ResourceDescriptor d, VerifiedScope s) {
            return r.action().equals(MODEL_USE) && !d.domainState().equals("ACTIVE") ? AccessReasonEnum.DOMAIN_DENIED : AccessReasonEnum.ALLOWED;
        }

        public AccessReasonEnum evaluateCurrent(TrustedActor actor, AccessRequest r, ResourceDescriptor d, VerifiedScope s) {
            tx.requireWritable();
            return evaluate(actor, r, d, s);
        }
    };

    @BeforeEach
    void setup() throws Exception {
        database = "acl_test_" + UUID.randomUUID().toString().replace("-", "");
        if (mysql == null) {
            source = new DriverManagerDataSource("jdbc:h2:mem:" + database + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000", "sa", "");
        } else {
            // 只能传专用隔离测试实例；随机库在用例结束删除，不连接业务库。
            new JdbcTemplate(new DriverManagerDataSource(mysql, "root", "platform-test")).execute("CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
            source = new DriverManagerDataSource(mysql.replace("/mysql?", "/" + database + "?"), "root", "platform-test");
        }
        jdbc = new JdbcTemplate(source);
        for (String script : List.of("identity", "workspace", "personal-policy-template", "resource-acl")) {
            String sql = Files.readString(Path.of("scripts/arte-platform-" + script + "-ddl-mysql.sql"));
            if (mysql == null) sql = sql.replaceAll("(?i)CHARACTER SET [a-z0-9_]+ COLLATE [a-z0-9_]+", "")
                    .replaceAll("(?i)ENGINE=InnoDB", "").replaceAll("(?im)^SET\\s+[^;]*;", "");
            try (var connection = source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            }
        }
        jdbc.execute("CREATE TABLE test_model(id VARCHAR(256) PRIMARY KEY,tenant_id VARCHAR(256),workspace_id VARCHAR(256),state VARCHAR(30),version BIGINT,created_by VARCHAR(256))");
        jdbc.execute("CREATE TABLE arte_rbac_user(id BIGINT PRIMARY KEY,user_name VARCHAR(64),status INT,create_time DATETIME(6))");
        int account = 0;
        for (String id : List.of("alice", "bob", "carol")) {
            account++;
            jdbc.update("INSERT INTO arte_rbac_user VALUES(?,?,1,CURRENT_TIMESTAMP)", account, id);
            principals.put(id, new Principal(id, PrincipalKindEnum.USER, PrincipalStatusEnum.ACTIVE));
            jdbc.update("INSERT INTO arte_platform_principal_identity(subject_id,provider,external_user_id,external_created_at,created_at) VALUES(?,'arte-rbac',?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, Integer.toString(account));
        }
        alice = actor("alice");
        bob = actor("bob");
        carol = actor("carol");
        tx = new JdbcPublicTransactionRunner(source, new DataSourceTransactionManager(source), 15);
        stores = new PlatformServiceTestFactory(source, tx, new PlatformJson(), null);
        grants = stores.grants();
        services = graph(stores.audit(), List.of(descriptor), List.of(condition));
        scope = services.personal().initialize(alice, meta("initialize")).value().workspace().scope();
        resource = new ResourceRef(scope.tenantId(), MODEL, "model-1");
        // 成员治理尚未开放；以真实存储和门控建立测试成员事实。
        for (String id : List.of("bob", "carol")) {
            tx.required(() -> {
                var gates = new WorkspaceGovernanceLocks(tx, stores.tenants(), stores.workspaces(), stores.tenantMembers(), stores.workspaceMembers(), stores.preferences());
                gates.tenant(id, scope.tenantId());
                assertTrue(stores.tenantMembers().insert(new TenantMembership("tenant-" + id, scope.tenantId(), id, TenantRoleEnum.MEMBER, MembershipStateEnum.ACTIVE, 0, 0)));
                assertTrue(stores.workspaceMembers().insert(new WorkspaceMembership("space-" + id, scope, id, id.equals("carol") ? WorkspaceRoleEnum.ADMIN : WorkspaceRoleEnum.MEMBER, MembershipStateEnum.ACTIVE, 0, 0)));
                return null;
            });
        }
    }

    @AfterEach
    void cleanup() {
        if (mysql != null && database != null)
            new JdbcTemplate(new DriverManagerDataSource(mysql, "root", "platform-test")).execute("DROP DATABASE " + database);
    }

    DefaultWorkspaceServices graph(AuditSink audit, List<ResourceDescriptorProvider> descriptors, List<DomainAccessConditionProvider> conditions) {
        return new DefaultWorkspaceServices(identity, tx, stores.tenants(), stores.workspaces(), stores.tenantMembers(), stores.workspaceMembers(),
                stores.policies(), stores.preferences(), stores.receipts(), stores.outbox(), audit, () -> UUID.randomUUID().toString(), clock,
                new DefaultWorkspacePolicies(stores.templates()), stores.facts(), List.of(), WorkspaceBrowseBudget.defaults(), System::nanoTime,
                grants, descriptors, conditions);
    }

    Optional<ResourceDescriptor> readDescriptor(ResourceRef ref, boolean current) {
        if (!ref.type().equals(MODEL)) return Optional.empty();
        return jdbc.query("SELECT * FROM test_model WHERE id=? AND tenant_id=?" + (current ? " FOR UPDATE" : ""),
                (row, n) -> new ResourceDescriptor(ref, new ResourceOwnership(row.getString("workspace_id") == null ? ResourceOwnershipTypeEnum.TENANT : ResourceOwnershipTypeEnum.WORKSPACE, row.getString("tenant_id"), row.getString("workspace_id")),
                        row.getString("state"), row.getLong("version"), row.getString("created_by")), ref.resourceId(), ref.tenantId()).stream().findFirst();
    }

    TrustedActor actor(String id) {
        return new TrustedActor(principals.get(id), now.get());
    }

    CommandMeta meta(String key) {
        return new CommandMeta(key, "acl-test");
    }

    AccessRequest request(Action action) {
        return new AccessRequest(scope, resource, action, "acl-test");
    }

    boolean allowed(TrustedActor actor, Action action) {
        return services.authorization().check(actor, request(action)).allowed();
    }

    ResourceAccess root() {
        return grants.findAccess(resource).orElseThrow();
    }

    ResourceAccess register() {
        return tx.required(() -> {
            jdbc.update("INSERT INTO test_model VALUES(?,?,?,'ACTIVE',0,'alice')", resource.resourceId(), scope.tenantId(), scope.workspaceId());
            return services.resources().registerCreated(alice, descriptor.findCurrent(resource).orElseThrow(), meta("register"));
        });
    }

    ResourceAccessCommands.GrantResource grantCommand(String key, long version, String recipient, Set<Action> actions) {
        return new ResourceAccessCommands.GrantResource(meta(key), scope, resource, version, new GrantRecipient.Subject(recipient), actions, null);
    }

    CommandReceipt<ResourceAccessViews.GrantResult> give(String key, String target, Set<Action> actions) {
        return services.resources().grant(alice, grantCommand(key, root().aclVersion(), target, actions));
    }

    long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    void revoke(ResourceAccessViews.GrantView grant, String key) {
        services.resources().revoke(alice, new ResourceAccessCommands.RevokeGrant(meta(key), scope, resource, grant.id(), root().aclVersion(), grant.version()));
    }

    @Test
    void registrationCreatesExplicitInitialGrantsAndNoAdministratorOverride() {
        register();
        assertEquals(ResourceVisibilityEnum.EXPLICIT, root().visibility());
        assertTrue(allowed(alice, MODEL_USE));
        assertTrue(allowed(alice, RESOURCE_ACCESS_MANAGE));
        assertFalse(allowed(bob, MODEL_READ));
        assertFalse(allowed(carol, MODEL_READ));
        var decision = services.authorization().check(alice, request(MODEL_USE));
        assertEquals(0L, decision.versions().aclVersion());
        assertEquals(0L, decision.versions().resourceVersion());
        assertEquals(8, services.resources().listGrants(alice, scope, resource, new PageQuery(1, 100)).total());
    }

    @Test
    void registerRequiresOriginalTransactionAndTrustedDescriptor() {
        var d = new ResourceDescriptor(resource, new ResourceOwnership(ResourceOwnershipTypeEnum.WORKSPACE, scope.tenantId(), scope.workspaceId()), "ACTIVE", 0, "alice");
        assertThrows(IllegalStateException.class, () -> services.resources().registerCreated(alice, d, meta("outside")));
        assertThrows(PlatformException.class, () -> tx.required(() -> {
            jdbc.update("INSERT INTO test_model VALUES(?,?,?,'ACTIVE',1,'alice')", resource.resourceId(), scope.tenantId(), scope.workspaceId());
            return services.resources().registerCreated(alice, d, meta("wrong-version"));
        }));
        assertEquals(0, count("test_model"));
        assertEquals(0, count("arte_platform_resource_access"));
    }

    @Test
    void grantsAreIndependentAndRevokeReceiptsRoundTripVoid() {
        register();
        var first = give("g1", "bob", Set.of(MODEL_USE)).value().grants().getFirst();
        var second = give("g2", "bob", Set.of(MODEL_USE)).value().grants().getFirst();
        revoke(first, "r1");
        assertTrue(allowed(bob, MODEL_USE));
        var command = new ResourceAccessCommands.RevokeGrant(meta("r2"), scope, resource, second.id(), root().aclVersion(), second.version());
        services.resources().revoke(alice, command);
        assertFalse(allowed(bob, MODEL_USE));
        assertEquals(CommandDeliveryEnum.REPLAYED, services.resources().revoke(alice, command).delivery());
        assertEquals(10, count("arte_platform_resource_grant"));
    }

    @Test
    void grantReplayNormalizesActionSetAndRejectsDifferentPayload() {
        register();
        var command = grantCommand("same", 0, "bob", new LinkedHashSet<>(List.of(MODEL_USE, MODEL_READ)));
        var applied = services.resources().grant(alice, command);
        var replay = services.resources().grant(alice, grantCommand("same", 0, "bob", new LinkedHashSet<>(List.of(MODEL_READ, MODEL_USE))));
        assertEquals(CommandDeliveryEnum.REPLAYED, replay.delivery());
        assertEquals(applied.value(), replay.value());
        assertThrows(PlatformException.class, () -> services.resources().grant(alice, grantCommand("same", 0, "bob", Set.of(MODEL_EDIT))));
        assertEquals(1, root().aclVersion());
    }

    @Test
    void subjectGenerationAndMembershipStateOverrideDirectGrant() {
        register();
        give("g", "bob", Set.of(MODEL_USE));
        assertTrue(allowed(bob, MODEL_USE));
        jdbc.update("UPDATE arte_platform_workspace_member SET state=1 WHERE subject_id='bob'");
        assertFalse(allowed(bob, MODEL_USE));
        jdbc.update("UPDATE arte_platform_workspace_member SET state=0,generation=1,version=2 WHERE subject_id='bob'");
        assertFalse(allowed(bob, MODEL_USE));
        give("new-membership", "bob", Set.of(MODEL_USE));
        assertTrue(allowed(bob, MODEL_USE));
    }

    @Test
    void visibilityChangeOnlyRevokesModeManagedSources() {
        register();
        give("direct", "bob", Set.of(MODEL_READ));
        services.resources().changeVisibility(alice, new ResourceAccessCommands.ChangeVisibility(meta("shared"), scope, resource, root().aclVersion(),
                ResourceVisibilityEnum.SPACE_SHARED, List.of(new VisibilityGrant(new GrantRecipient.Space(scope), Set.of(MODEL_USE), null))));
        assertTrue(allowed(bob, MODEL_USE));
        assertTrue(allowed(carol, MODEL_USE));
        services.resources().changeVisibility(alice, new ResourceAccessCommands.ChangeVisibility(meta("private"), scope, resource, root().aclVersion(), ResourceVisibilityEnum.EXPLICIT, List.of()));
        assertFalse(allowed(bob, MODEL_USE));
        assertTrue(allowed(bob, MODEL_READ));
        assertFalse(allowed(carol, MODEL_USE));
    }

    @Test
    void sharingCannotDelegateGovernanceOrActionsNotHeld() {
        register();
        give("share-to-bob", "bob", Set.of(MODEL_USE, MODEL_SHARE));
        assertThrows(PlatformException.class, () -> services.resources().grant(bob, grantCommand("escalate", root().aclVersion(), "carol", Set.of(RESOURCE_ACCESS_MANAGE))));
        assertThrows(PlatformException.class, () -> services.resources().grant(bob, grantCommand("edit", root().aclVersion(), "carol", Set.of(MODEL_EDIT))));
        var shared = services.resources().grant(bob, grantCommand("use", root().aclVersion(), "carol", Set.of(MODEL_USE))).value().grants().getFirst();
        assertTrue(allowed(carol, MODEL_USE));
        services.resources().revoke(bob, new ResourceAccessCommands.RevokeGrant(meta("revoke-own"), scope, resource, shared.id(), root().aclVersion(), shared.version()));
        assertFalse(allowed(carol, MODEL_USE));
    }

    @Test
    void expiryDomainAndArchivedStateAreCheckedAgain() {
        register();
        services.resources().grant(alice, new ResourceAccessCommands.GrantResource(meta("ttl"), scope, resource, 0, new GrantRecipient.Subject("bob"), Set.of(MODEL_USE), now.get().plusSeconds(10)));
        assertTrue(allowed(bob, MODEL_USE));
        now.set(now.get().plusSeconds(10));
        assertFalse(allowed(bob, MODEL_USE));
        give("read", "bob", Set.of(MODEL_READ, MODEL_USE));
        jdbc.update("UPDATE test_model SET state='DRAFT',version=1");
        assertFalse(allowed(bob, MODEL_USE));
        jdbc.update("UPDATE test_model SET state='ACTIVE'");
        jdbc.update("UPDATE arte_platform_workspace SET state=1");
        assertTrue(allowed(bob, MODEL_READ));
        assertFalse(allowed(bob, MODEL_USE));
    }

    @Test
    void policyDenialWinsOverSubjectGrant() {
        register();
        give("use", "bob", Set.of(MODEL_USE));
        jdbc.update("UPDATE arte_platform_workspace_member SET role=3 WHERE subject_id='bob'");
        assertEquals(AccessReasonEnum.POLICY_DENIED, services.authorization().check(bob, request(MODEL_USE)).reason());
    }

    @Test
    void missingOrConflictingProvidersFailClosed() {
        register();
        assertEquals(AccessReasonEnum.AUTHORITY_UNAVAILABLE, graph(stores.audit(), List.of(), List.of(condition)).authorization().check(alice, request(MODEL_READ)).reason());
        assertEquals(AccessReasonEnum.AUTHORITY_UNAVAILABLE, graph(stores.audit(), List.of(descriptor, descriptor), List.of(condition)).authorization().check(alice, request(MODEL_READ)).reason());
        assertEquals(AccessReasonEnum.AUTHORITY_UNAVAILABLE, graph(stores.audit(), List.of(descriptor), List.of()).authorization().check(alice, request(MODEL_READ)).reason());
    }

    @Test
    void staleAclVersionCannotOverwriteAndCrossSpaceAccessFails() {
        register();
        give("first", "bob", Set.of(MODEL_READ));
        assertThrows(PlatformException.class, () -> services.resources().grant(alice, grantCommand("stale", 0, "bob", Set.of(MODEL_EDIT))));
        var other = services.workspaces().create(alice, new WorkspaceCommands.CreateWorkspace(meta("other"), scope.tenantId(), "other", "")).value().scope();
        assertFalse(services.authorization().check(alice, new AccessRequest(other, resource, MODEL_USE, "test")).allowed());
        assertThrows(PlatformException.class, () -> services.resources().grant(alice, new ResourceAccessCommands.GrantResource(meta("cross"), scope, resource, root().aclVersion(), new GrantRecipient.Space(other), Set.of(MODEL_READ), null)));
    }

    @Test
    void finalGuardUsesCurrentGrantsInsteadOfOuterSnapshot() throws Exception {
        register();
        var grant = give("g", "bob", Set.of(MODEL_USE)).value().grants().getFirst();
        var outer = new org.springframework.transaction.support.TransactionTemplate(new DataSourceTransactionManager(source));
        outer.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
        outer.execute(status -> {
            assertTrue(grants.findGrants(resource, new PageQuery(1, 100)).records().stream().anyMatch(g -> g.id().equals(grant.id()) && g.state() == GrantStateEnum.ACTIVE));
            try (var executor = Executors.newSingleThreadExecutor()) {
                executor.submit(() -> revoke(grant, "remove")).get(5, TimeUnit.SECONDS);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            assertThrows(PlatformException.class, () -> services.writeGuard().inCurrentTransaction(bob, List.of(request(MODEL_USE)), () -> {
                fail("revoked write ran");
                return true;
            }));
            return null;
        });
    }

    @Test
    void concurrentCasAllowsOneGrantAndLoserLeavesNoExtraSource() throws Exception {
        register();
        try (var executor = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var tasks = new ArrayList<Future<Boolean>>();
            for (int i = 0; i < 2; i++) {
                final int n = i;
                tasks.add(executor.submit(() -> {
                    start.await();
                    try {
                        services.resources().grant(alice, grantCommand("race-" + n, 0, "bob", Set.of(MODEL_USE)));
                        return true;
                    } catch (PlatformException e) {
                        assertEquals(PlatformErrorCodeEnum.VERSION_CONFLICT, e.getCode());
                        return false;
                    }
                }));
            }
            start.countDown();
            int winners = 0;
            for (var task : tasks) if (task.get(10, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
            assertEquals(9, count("arte_platform_resource_grant"));
        }
    }

    @Test
    void auditFailureRollsBackAclDomainEpochReceiptAndEvents() {
        long events = count("arte_platform_outbox");
        long receipts = count("arte_platform_command");
        long epoch = stores.workspaces().find(scope).orElseThrow().authEpoch();
        var failing = graph(entry -> {
            throw new IllegalStateException("audit unavailable");
        }, List.of(descriptor), List.of(condition));
        assertThrows(IllegalStateException.class, () -> tx.required(() -> {
            jdbc.update("INSERT INTO test_model VALUES(?,?,?,'ACTIVE',0,'alice')", resource.resourceId(), scope.tenantId(), scope.workspaceId());
            return failing.resources().registerCreated(alice, descriptor.findCurrent(resource).orElseThrow(), meta("failure"));
        }));
        assertEquals(0, count("test_model"));
        assertEquals(0, count("arte_platform_resource_access"));
        assertEquals(0, count("arte_platform_resource_grant"));
        assertEquals(events, count("arte_platform_outbox"));
        assertEquals(receipts, count("arte_platform_command"));
        assertEquals(epoch, stores.workspaces().find(scope).orElseThrow().authEpoch());
    }

    @Test
    void corruptedIdentityOrAclOwnershipCannotAuthorize() {
        register();
        jdbc.update("UPDATE arte_platform_resource_access SET resource_id='different'");
        assertEquals(AccessReasonEnum.AUTHORITY_UNAVAILABLE, services.authorization().check(alice, request(MODEL_READ)).reason());
    }

    @Test
    void replayDoesNotRestoreRevokedAuthorityAndBatchUsesSameRules() {
        register();
        var command = grantCommand("g", 0, "bob", Set.of(MODEL_USE));
        services.resources().grant(alice, command);
        var checks = services.authorization().batchCheck(bob, List.of(request(MODEL_USE), request(MODEL_EDIT)));
        assertTrue(checks.get(0).allowed());
        assertFalse(checks.get(1).allowed());
        jdbc.update("UPDATE arte_platform_workspace_member SET state=1 WHERE subject_id='alice'");
        assertThrows(PlatformException.class, () -> services.resources().grant(alice, command));
        assertFalse(allowed(alice, MODEL_USE));
    }

    @Test
    void registrationReplayPreservesInitialGrantCountAndEpoch() {
        var created = register();
        long epoch = stores.workspaces().find(scope).orElseThrow().authEpoch();
        var replay = tx.required(() -> services.resources().registerCreated(alice, descriptor.findCurrent(resource).orElseThrow(), meta("register")));
        assertEquals(created, replay);
        assertEquals(8, count("arte_platform_resource_grant"));
        assertEquals(epoch, stores.workspaces().find(scope).orElseThrow().authEpoch());
    }

    @Test
    void ordinaryReadCanUseReadOnlySnapshotButWriteGateCannot() {
        register();
        assertTrue(tx.readOnly(() -> allowed(alice, MODEL_USE)));
        assertThrows(IllegalStateException.class, () -> tx.readOnly(() -> services.writeGuard().inCurrentTransaction(alice, List.of(request(MODEL_USE)), () -> true)));
    }

    @Test
    void finalGuardUsesCurrentDomainStateWhenOrdinaryDescriptorIsStale() {
        register();
        ResourceDescriptor saved = descriptor.find(resource).orElseThrow();
        var stale = new ResourceDescriptorProvider() {
            public boolean supports(ResourceType type) {
                return type.equals(MODEL);
            }

            public Optional<ResourceDescriptor> find(ResourceRef ref) {
                return Optional.of(saved);
            }

            public Optional<ResourceDescriptor> findCurrent(ResourceRef ref) {
                return descriptor.findCurrent(ref);
            }
        };
        var graph = graph(stores.audit(), List.of(stale), List.of(condition));
        jdbc.update("UPDATE test_model SET state='DRAFT',version=1");
        assertTrue(graph.authorization().check(alice, request(MODEL_USE)).allowed());
        tx.required(() -> {
            assertThrows(PlatformException.class, () -> graph.writeGuard().inCurrentTransaction(alice, List.of(request(MODEL_USE)), () -> {
                fail("stale domain allowed");
                return true;
            }));
            return null;
        });
    }

    @Test
    void reentrantGuardDoesNotCacheAllowAcrossDomainWrites() {
        register();
        tx.required(() -> {
            services.writeGuard().inCurrentTransaction(alice, List.of(request(MODEL_USE)), () -> {
                jdbc.update("UPDATE test_model SET state='DRAFT',version=1");
                return true;
            });
            assertThrows(PlatformException.class, () -> services.writeGuard().inCurrentTransaction(alice, List.of(request(MODEL_USE)), () -> true));
            return null;
        });
    }

    @Test
    void duplicateGrantSourceCannotBeInsertedUnderAnotherId() {
        register();
        tx.required(() -> services.writeGuard().inCurrentTransaction(alice, List.of(request(MODEL_READ)), () -> {
            var existing = grants.findCurrentGrants(resource, new PageQuery(1, 100)).records().getFirst();
            var copy = new ResourceGrant("copy", existing.resource(), existing.target(), existing.action(), existing.state(), existing.expiresAt(),
                    existing.source(), existing.sourceOperationId(), existing.issuedBy(), existing.createdAt(), 0);
            assertFalse(grants.insertGrantIfAbsent(copy));
            return null;
        }));
        assertEquals(8, count("arte_platform_resource_grant"));
    }

    @Test
    void roleSharingAndNewMembershipUseCurrentRoleWithoutImplyingManagement() {
        register();
        services.resources().changeVisibility(alice, new ResourceAccessCommands.ChangeVisibility(meta("role"), scope, resource, 0,
                ResourceVisibilityEnum.SPACE_SHARED, List.of(new VisibilityGrant(new GrantRecipient.SpaceRole(scope, WorkspaceRoleEnum.MEMBER), Set.of(MODEL_READ), null))));
        assertTrue(allowed(bob, MODEL_READ));
        assertFalse(allowed(carol, MODEL_READ));
        assertFalse(allowed(bob, RESOURCE_ACCESS_MANAGE));
        assertThrows(PlatformException.class, () -> services.resources().listGrants(bob, scope, resource, new PageQuery(1, 100)));
        jdbc.update("UPDATE arte_platform_workspace_member SET generation=1 WHERE subject_id='bob'");
        assertTrue(allowed(bob, MODEL_READ));
        jdbc.update("UPDATE arte_platform_workspace_member SET role=1 WHERE subject_id='bob'");
        assertFalse(allowed(bob, MODEL_READ));
    }

    @Test
    void rejectedVisibilityRequestRollsBackItsIntermediateWrites() {
        register();
        var before = root();
        long grantsBefore = count("arte_platform_resource_grant");
        assertThrows(PlatformException.class, () -> services.resources().changeVisibility(alice,
                new ResourceAccessCommands.ChangeVisibility(meta("invalid-share"), scope, resource, 0, ResourceVisibilityEnum.SPACE_SHARED,
                        List.of(new VisibilityGrant(new GrantRecipient.Space(scope), Set.of(MODEL_READ), null),
                                new VisibilityGrant(new GrantRecipient.Space(scope), Set.of(MODEL_PUBLISH), null)))));
        assertEquals(before, root());
        assertEquals(grantsBefore, count("arte_platform_resource_grant"));
        assertFalse(allowed(bob, MODEL_READ));
    }

    @Test
    void workspaceGovernorCanRevokeWithoutGainingPrivateReadOrGrantPower() {
        tx.required(() -> services.writeGuard().inCurrentTransaction(carol,
                List.of(com.arte.core.application.access.DefaultAuthorizationService.workspaceRequest(scope, MODEL_CREATE, "test-create")), () -> {
                    jdbc.update("INSERT INTO test_model VALUES(?,?,?,'ACTIVE',0,'carol')", resource.resourceId(), scope.tenantId(), scope.workspaceId());
                    return services.resources().registerCreated(carol, descriptor.findCurrent(resource).orElseThrow(), meta("carol-model"));
                }));
        assertFalse(allowed(alice, MODEL_READ));
        assertFalse(allowed(alice, RESOURCE_ACCESS_MANAGE));
        assertThrows(PlatformException.class, () -> give("self-escalation", "alice", Set.of(MODEL_READ)));
        var grant = services.resources().listGrants(carol, scope, resource, new PageQuery(1, 100)).records().stream()
                .filter(g -> g.action().equals(MODEL_USE)).findFirst().orElseThrow();
        revoke(grant, "governance-revoke");
        assertFalse(allowed(carol, MODEL_USE));
        assertFalse(allowed(alice, MODEL_READ));
        assertThrows(PlatformException.class, () -> services.resources().listGrants(alice, scope, resource, new PageQuery(1, 100)));
    }

    @Test
    void spaceGrantCannotBypassExplicitModeAndKeepsIndependentHistory() {
        register();
        services.resources().grant(alice, new ResourceAccessCommands.GrantResource(meta("space-grant"), scope, resource, 0,
                new GrantRecipient.Space(scope), Set.of(MODEL_USE), null));
        assertFalse(allowed(bob, MODEL_USE));
        services.resources().changeVisibility(alice, new ResourceAccessCommands.ChangeVisibility(meta("shared"), scope, resource, root().aclVersion(),
                ResourceVisibilityEnum.SPACE_SHARED, List.of(new VisibilityGrant(new GrantRecipient.Space(scope), Set.of(MODEL_READ), null))));
        assertTrue(allowed(bob, MODEL_USE));
        services.resources().changeVisibility(alice, new ResourceAccessCommands.ChangeVisibility(meta("explicit"), scope, resource, root().aclVersion(), ResourceVisibilityEnum.EXPLICIT, List.of()));
        assertFalse(allowed(bob, MODEL_USE));
        assertEquals(GrantStateEnum.ACTIVE, grants.findGrants(resource, new PageQuery(1, 100)).records().stream()
                .filter(g -> g.action().equals(MODEL_USE) && g.target() instanceof GrantTarget.Space).findFirst().orElseThrow().state());
    }

    @Test
    void tenantOwnedResourceUsesTenantGenerationAndCannotBeSelfRegistered() {
        // 模拟后续受信迁移已经登记的租户资源，不通过客户端创建或推算旧模型归属。
        tx.required(() -> {
            var gates = new WorkspaceGovernanceLocks(tx, stores.tenants(), stores.workspaces(), stores.tenantMembers(), stores.workspaceMembers(), stores.preferences());
            gates.tenant("alice", scope.tenantId());
            jdbc.update("INSERT INTO test_model VALUES(?,?,NULL,'ACTIVE',0,'alice')", resource.resourceId(), scope.tenantId());
            assertTrue(grants.insertAccess(new ResourceAccess(resource, new ResourceOwnership(ResourceOwnershipTypeEnum.TENANT, scope.tenantId(), null), ResourceVisibilityEnum.EXPLICIT, 0)));
            for (var action : Set.of(MODEL_USE, RESOURCE_ACCESS_MANAGE))
                assertTrue(grants.insertGrantIfAbsent(new ResourceGrant(UUID.randomUUID().toString(), resource,
                        new GrantTarget.Subject("alice", new CommandScope(scope.tenantId(), null), 0), action, GrantStateEnum.ACTIVE, null,
                        GrantSourceEnum.MANAGEMENT, "trusted-seed", "alice", now.get(), 0)));
            return null;
        });
        give("tenant-use", "bob", Set.of(MODEL_USE));
        assertTrue(allowed(bob, MODEL_USE));
        jdbc.update("UPDATE arte_platform_workspace_member SET generation=1 WHERE subject_id='bob'");
        assertTrue(allowed(bob, MODEL_USE));
        jdbc.update("UPDATE arte_platform_tenant_member SET generation=1 WHERE subject_id='bob'");
        assertFalse(allowed(bob, MODEL_USE));
        var failure = assertThrows(PlatformException.class, () -> tx.required(() -> services.resources().registerCreated(alice, descriptor.findCurrent(resource).orElseThrow(), meta("tenant-register"))));
        assertEquals(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED, failure.getCode());
    }

}
