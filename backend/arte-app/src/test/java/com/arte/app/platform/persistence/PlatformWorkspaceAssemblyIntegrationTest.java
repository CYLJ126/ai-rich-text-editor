package com.arte.app.platform.persistence;

import cn.hutool.json.JSONObject;
import com.arte.app.platform.identity.PlatformIdentityConfiguration;
import com.arte.app.platform.identity.RbacAccountMapperConfiguration;
import com.arte.app.platform.identity.RbacAccountReader;
import com.arte.app.platform.workspace.PlatformWorkspaceConfiguration;
import com.arte.app.platform.workspace.ReactiveWorkspaceOperations;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.app.service.platform.AuditSinkServiceImpl;
import com.arte.app.web.controller.platform.MembershipGovernanceController;
import com.arte.app.web.controller.platform.TenantDirectoryController;
import com.arte.app.web.controller.platform.WorkspaceController;
import com.arte.core.application.workspace.DefaultWorkspaceServices;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.workspace.MembershipStateEnum;
import com.arte.core.enums.workspace.TenantRoleEnum;
import com.arte.core.enums.workspace.WorkspaceRoleEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.UserOnlineInfo;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.OrganizationTenantPolicyTemplate;
import com.arte.core.pojo.workspace.PolicyRules;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import com.arte.core.pojo.workspace.WorkspaceViews;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Signal;
import reactor.util.function.Tuple2;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

import static com.arte.core.constant.platform.PlatformActions.WORKSPACE_READ;
import static com.arte.core.constant.platform.PlatformResourceTypes.WORKSPACE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * 实际生产配置、组件扫描和 Mapper；默认 H2，也可沿用隔离 MySQL 参数运行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/10 18:22 ✾
 */
class PlatformWorkspaceAssemblyIntegrationTest {
    private final PlatformPersistenceIntegrationTest fixture = new PlatformPersistenceIntegrationTest();
    private static final Duration WAIT = Duration.ofSeconds(20);

    @Configuration(proxyBeanMethods = false)
    @ComponentScan(basePackageClasses = AuditSinkServiceImpl.class)
    static class Stores {
    }

    @BeforeEach
    void setup() throws Exception {
        fixture.setup();
    }

    @AfterEach
    void cleanup() {
        fixture.cleanup();
    }

    private ApplicationContextRunner context() {
        return new ApplicationContextRunner()
                .withUserConfiguration(PlatformWorkspaceConfiguration.class, PlatformIdentityConfiguration.class,
                        PlatformPersistenceConfiguration.class, MembershipGovernanceController.class, Stores.class)
                .withPropertyValues("arte.platform.identity.enabled=true", "arte.platform.identity.legacy-user-id-ceiling=0",
                        "arte.platform.persistence.enabled=true", "arte.platform.workspace.enabled=true")
                .withBean("appDataSource", DataSource.class, () -> fixture.source)
                .withBean(RbacAccountReader.class, () -> new RbacAccountReader(fixture.source,
                        new RbacAccountMapperConfiguration().identityUserMapper(fixture.source)))
                .withBean(RedissonClient.class, () -> {
                    String redis = System.getProperty("platform.test.redis");
                    if (redis == null) return mock(RedissonClient.class);
                    var config = new Config();
                    config.useSingleServer().setAddress(redis).setConnectionMinimumIdleSize(1)
                            .setConnectionPoolSize(4).setSubscriptionConnectionMinimumIdleSize(1)
                            .setSubscriptionConnectionPoolSize(2);
                    return Redisson.create(config);
                }, bean -> bean.setDestroyMethodName("shutdown"));
    }

    private Authentication login(long id, String name) {
        var online = new UserOnlineInfo().setId(id).setUserName(name)
                .setAccountCreatedAt(fixture.jdbc.queryForObject("SELECT create_time FROM arte_rbac_user WHERE id=?",
                        (row, index) -> row.getObject(1, LocalDateTime.class), id));
        return UsernamePasswordAuthenticationToken.authenticated(new JwtUserDto(online), null, List.of());
    }

    private CommandMeta meta(String key) {
        return new CommandMeta(key, "assembly-test");
    }

    @Test
    void reactiveSpringBeansCompleteWorkflowWithoutBlockingCallerOrUserContext() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            var api = c.getBean(ReactiveWorkspaceOperations.class);
            var auth = login(1, "alice");
            var init = api.initializePersonal(auth, meta("init"));
            assertEquals(0, fixture.count("tenant")); // 构建 Mono 不访问数据库。
            var first = init.block(WAIT);
            var replay = api.initializePersonal(auth, meta("init")).block(WAIT);
            assertEquals(first.operationId(), replay.operationId());
            var defaultScope = first.value().workspace().scope();
            var created = api.create(auth, new WorkspaceCommands.CreateWorkspace(meta("create"),
                    defaultScope.tenantId(), "Second", "description")).block(WAIT);
            var scope = created.value().scope();
            assertEquals(2, api.listAccessible(auth, new PageQuery(1, 20)).block(WAIT).total());
            assertEquals("Second", api.get(auth, scope).block(WAIT).name());
            assertNotNull(api.verifyScope(auth, scope).block(WAIT));
            var renamed = api.rename(auth, new WorkspaceCommands.RenameWorkspace(meta("rename"), scope,
                    created.resultVersion(), "Renamed", "description")).block(WAIT);
            assertEquals("Renamed", renamed.value().name());
            var conflict = assertThrows(PlatformException.class, () -> api.rename(auth,
                    new WorkspaceCommands.RenameWorkspace(meta("stale"), scope, 0, "Stale", null)).block(WAIT));
            assertEquals(PlatformErrorCodeEnum.VERSION_CONFLICT, conflict.getCode());
            var preference = api.getPreference(auth).block(WAIT);
            var updated = api.updatePreference(auth, new WorkspaceCommands.UpdatePreference(meta("preference"),
                    preference.version(), scope, scope, Set.of(scope))).block(WAIT);
            assertEquals(scope, updated.value().startupScope());
            assertEquals(defaultScope, updated.value().defaultScope());
            var request = read(scope);
            assertTrue(api.check(auth, request).block(WAIT).allowed());
            assertNotNull(api.require(auth, request).block(WAIT));
            assertTrue(api.batchCheck(auth, List.of(request)).block(WAIT).getFirst().allowed());
            assertEquals(2, fixture.count("workspace"));
            assertTrue(fixture.count("audit") > 0);
            assertEquals(fixture.count("audit"), fixture.count("outbox"));
        });
    }

    @Test
    void authenticationSnapshotSurvivesSessionMutationAndStillRechecksDatabase() {
        context().run(c -> {
            var api = c.getBean(ReactiveWorkspaceOperations.class);
            var auth = login(1, "alice");
            var pending = api.initializePersonal(auth, meta("captured"));
            ((JwtUserDto) auth.getPrincipal()).userOnlineInfo().setId(99L).setUserName("attacker");
            assertNotNull(pending.block(WAIT));
            var another = api.getPreference(login(1, "alice"));
            fixture.jdbc.update("UPDATE arte_rbac_user SET status=3 WHERE id=1");
            assertEquals(PlatformErrorCodeEnum.UNAUTHENTICATED,
                    assertThrows(PlatformException.class, () -> another.block(WAIT)).getCode());
        });
    }

    @Test
    void noAnonymousOrCrossUserAccessAndNoInitializationOnContextStartup() {
        context().run(c -> {
            assertEquals(0, fixture.count("tenant"));
            var api = c.getBean(ReactiveWorkspaceOperations.class);
            assertThrows(PlatformException.class, () -> api.initializePersonal(null, meta("anonymous")).block(WAIT));
            var scope = api.initializePersonal(login(1, "alice"), meta("init")).block(WAIT).value().workspace().scope();
            fixture.jdbc.update("INSERT INTO arte_rbac_user VALUES(2,'bob',1,?)",
                    java.sql.Timestamp.valueOf("2026-10-10 10:00:00"));
            assertThrows(PlatformException.class, () -> api.get(login(2, "bob"), scope).block(WAIT));
            assertEquals(1, fixture.count("workspace"));
        });
    }

    @Test
    void composedWritesCommitTogetherAndCallbackFailureRollsBack() {
        context().run(c -> {
            var api = c.getBean(ReactiveWorkspaceOperations.class);
            var auth = login(1, "alice");
            var scope = api.initializePersonal(auth, meta("init")).block(WAIT).value().workspace().scope();
            fixture.jdbc.execute("CREATE TABLE assembly_business(id INT PRIMARY KEY, value_text VARCHAR(32))");
            String thread = api.inWorkspaceTransaction(auth, List.of(read(scope)), () -> {
                assertTrue(c.getBean(JdbcPublicTransactionRunner.class).isTransactionActive());
                fixture.jdbc.update("INSERT INTO assembly_business VALUES(1,'committed')");
                return Thread.currentThread().getName();
            }).block(WAIT);
            assertTrue(thread.startsWith("platform-persistence-"));
            assertThrows(IllegalStateException.class, () -> api.inWorkspaceTransaction(auth, List.of(read(scope)), () -> {
                fixture.jdbc.update("INSERT INTO assembly_business VALUES(2,'rolled-back')");
                throw new IllegalStateException("failure");
            }).block(WAIT));
            assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM assembly_business", Integer.class));
            assertThrows(IllegalArgumentException.class, () -> api.inWorkspaceTransaction(auth, List.of(read(scope)), () -> {
                fixture.jdbc.update("INSERT INTO assembly_business VALUES(3,'async-invalid')");
                return Mono.just("wrong-boundary");
            }).block(WAIT));
            assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM assembly_business", Integer.class));
        });
    }

    @Test
    void independentSpringInstancesUseDatabaseUniquenessForConcurrentInitialization() {
        context().run(first -> context().run(second -> {
            var a = first.getBean(ReactiveWorkspaceOperations.class);
            var b = second.getBean(ReactiveWorkspaceOperations.class);
            assertNotSame(first.getBean(DefaultWorkspaceServices.class), second.getBean(DefaultWorkspaceServices.class));
            var requests = new ArrayList<Mono<Scope>>();
            for (int i = 0; i < 12; i++) {
                requests.add((i % 2 == 0 ? a : b).initializePersonal(login(1, "alice"), meta("node-init-" + i))
                        .map(r -> r.value().workspace().scope()));
            }
            var scopes = Mono.zip(requests, values -> Arrays.stream(values).distinct().count()).block(WAIT);
            assertEquals(1L, scopes);
            assertEquals(1, fixture.count("tenant"));
            assertEquals(1, fixture.count("workspace"));
            assertEquals(12, fixture.count("command"));
            assertEquals(1, fixture.count("outbox"));
        }));
    }

    @Test
    void publishingDatabaseRevisionOnlyAffectsNewPersonalTenants() {
        final Scope[] scope = new Scope[1];
        context().run(c -> scope[0] = c.getBean(ReactiveWorkspaceOperations.class)
                .initializePersonal(login(1, "alice"), meta("init")).block(WAIT).value().workspace().scope());
        var initialRules = fixture.policies.findTenantPolicy(scope[0].tenantId()).orElseThrow().rules();
        var nextRules = new com.arte.core.pojo.workspace.PolicyRules(Set.of(com.arte.core.constant.platform.PlatformResourceTypes.ARTICLE),
                java.util.Map.of(), false, false, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        fixture.jdbc.update("INSERT INTO arte_platform_personal_policy_template VALUES(2,?,false,1,1,'test',CURRENT_TIMESTAMP)", fixture.json.write(nextRules));
        fixture.jdbc.update("UPDATE arte_platform_personal_policy_template_head SET version=2 WHERE id=1 AND version=1");
        fixture.jdbc.update("INSERT INTO arte_rbac_user VALUES(2,'bob',1,?)", java.sql.Timestamp.valueOf("2026-10-10 10:00:00"));
        context().run(c -> {
                    var api = c.getBean(ReactiveWorkspaceOperations.class);
                    api.initializePersonal(login(1, "alice"), meta("new-key")).block(WAIT);
                    assertEquals(4, c.getBean(com.arte.core.spi.workspace.PolicyStore.class)
                            .findTenantPolicy(scope[0].tenantId()).orElseThrow().rules().allowedCreationTypes().size());
            assertEquals(initialRules, fixture.policies.findTenantPolicy(scope[0].tenantId()).orElseThrow().rules());
            assertEquals(1L, fixture.policies.findTenantPolicy(scope[0].tenantId()).orElseThrow().personalTemplateVersion());
            var bob = api.initializePersonal(login(2, "bob"), meta("bob-init")).block(WAIT).value().workspace().scope();
            var bobPolicy = fixture.policies.findTenantPolicy(bob.tenantId()).orElseThrow();
            assertEquals(nextRules, bobPolicy.rules());
            assertEquals(2L, bobPolicy.personalTemplateVersion());
            assertEquals(0, bobPolicy.version());
        });
    }

    @Test
    void missingOrUnsupportedTemplateFailsClosedAndDoesNotAffectExistingInitialization() {
        context().run(c -> {
            var api = c.getBean(ReactiveWorkspaceOperations.class);
            var alice = login(1, "alice");
            var scope = api.initializePersonal(alice, meta("alice-init")).block(WAIT).value().workspace().scope();
            fixture.jdbc.update("INSERT INTO arte_rbac_user VALUES(2,'bob',1,?)", java.sql.Timestamp.valueOf("2026-10-10 10:00:00"));
            fixture.jdbc.update("DELETE FROM arte_platform_personal_policy_template_head");
            assertEquals(scope, api.initializePersonal(alice, meta("existing-without-template")).block(WAIT).value().workspace().scope());
            var missing = assertThrows(PlatformException.class, () -> api.initializePersonal(login(2, "bob"), meta("bob-init")).block(WAIT));
            assertEquals(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, missing.getCode());
            assertEquals(1, fixture.count("tenant"));
            fixture.jdbc.update("INSERT INTO arte_platform_personal_policy_template_head VALUES(1,1)");
            fixture.jdbc.update("UPDATE arte_platform_personal_policy_template SET policy_schema_version=2 WHERE version=1");
            var unsupported = assertThrows(PlatformException.class, () -> api.initializePersonal(login(2, "bob"), meta("bob-init")).block(WAIT));
            assertEquals(PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE, unsupported.getCode());
            assertEquals(1, fixture.count("tenant"));
        });
    }

    private AccessRequest read(Scope scope) {
        return new AccessRequest(scope, new ResourceRef(scope.tenantId(), WORKSPACE, scope.workspaceId()), WORKSPACE_READ, "assembly");
    }

    @Test
    void organizationAndTenantNameCommandsUseExplicitPermissionsAndRecoverWithoutCreatingSpaces() throws Exception {
        fixture.jdbc.execute("CREATE TABLE arte_rbac_relation(source VARCHAR(256), binding_type VARCHAR(96), target VARCHAR(256))");
        fixture.jdbc.execute("CREATE TABLE arte_rbac_role(role_code VARCHAR(96), status VARCHAR(8))");
        fixture.jdbc.execute("CREATE TABLE arte_rbac_menu(menu_code VARCHAR(32))");
        fixture.jdbc.execute("CREATE TABLE arte_rbac_menu_operation(menu_code VARCHAR(32), operation_code VARCHAR(32), operation_name VARCHAR(64),"
                + " status INT, description VARCHAR(256), create_by VARCHAR(32), create_time TIMESTAMP, UNIQUE(menu_code, operation_code))");
        fixture.jdbc.update("INSERT INTO arte_rbac_menu VALUES('EnvironmentTenants')");
        try (java.sql.Connection connection = fixture.source.getConnection()) {
            org.springframework.core.io.Resource install = new org.springframework.core.io.FileSystemResource("scripts/arte-tenant-governance-rbac-dml-mysql.sql");
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection, install);
            org.springframework.jdbc.datasource.init.ScriptUtils.executeSqlScript(connection, install);
        }
        assertEquals(2, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM arte_rbac_menu_operation", Integer.class));
        assertEquals(0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM arte_rbac_relation", Integer.class));
        fixture.jdbc.update("INSERT INTO arte_rbac_relation VALUES('alice','user_to_operation','admin')");
        fixture.jdbc.update("INSERT INTO arte_rbac_user VALUES(2,'bob',1,?)", java.sql.Timestamp.valueOf("2026-10-10 10:00:00"));
        com.arte.core.pojo.workspace.PolicyRules rules = new com.arte.core.pojo.workspace.PolicyRules(
                Set.of(com.arte.core.constant.platform.PlatformResourceTypes.ARTICLE), java.util.Map.of(), false, false,
                Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        com.arte.core.pojo.workspace.OrganizationTenantPolicyTemplate template =
                new com.arte.core.pojo.workspace.OrganizationTenantPolicyTemplate(7, rules, false, 1, 1);
        String root = "/workspace/";
        context().withPropertyValues("arte.platform.tenant-governance.organization-creation-enabled=true",
                        "arte.platform.tenant-governance.organization-policy-template=" + fixture.json.write(template))
                .run(c -> {
                    assertNull(c.getStartupFailure());
                    Authentication alice = login(1, "alice");
                    Authentication bob = login(2, "bob");
                    ReactiveWorkspaceOperations workspaces = c.getBean(ReactiveWorkspaceOperations.class);
                    try (ManagementHttpHarness http = new ManagementHttpHarness(
                            new com.arte.app.web.controller.platform.TenantDirectoryController(
                                    c.getBean(com.arte.app.platform.workspace.ReactiveTenantQueries.class)),
                            new com.arte.app.web.controller.platform.WorkspaceController(workspaces))) {
                        String create = fixture.json.write(java.util.Map.of("name", "Team One"));
                        assertFalse(http.ok(root + "getTenantCreationOptions", "{}", null, alice)
                                .getJSONObject("data").getBool("organizationAllowed"));
                        http.rejected(root + "createOrganization", create, "admin-is-not-grant", alice, 403, "ACCESS_DENIED");
                        assertEquals(0, fixture.count("tenant"));
                        fixture.jdbc.update("INSERT INTO arte_rbac_relation VALUES('alice','user_to_operation','EnvironmentTenants:create')");
                        assertTrue(http.ok(root + "getTenantCreationOptions", "{}", null, alice)
                                .getJSONObject("data").getBool("organizationAllowed"));
                        http.rejected(root + "createOrganization", fixture.json.write(java.util.Map.of("name", "Team", "type", 0)),
                                "forged-type", alice, 400, "INVALID_ARGUMENT");
                        cn.hutool.json.JSONObject created = http.ok(root + "createOrganization", create, "org", alice).getJSONObject("data");
                        String tenant = created.getJSONObject("value").getStr("id");
                        String createReceipt = fixture.json.write(java.util.Map.of("operationId", created.getStr("operationId")));
                        assertEquals(created.getStr("operationId"), http.ok(root + "createOrganization", create, "org", alice)
                                .getJSONObject("data").getStr("operationId"));
                        assertEquals(1, http.ok(root + "getWorkspaceCommandReceipt", createReceipt, null, alice)
                                .getJSONObject("data").getInt("delivery"));
                        http.rejected(root + "getWorkspaceCommandReceipt", createReceipt, null, bob, 404, "NOT_FOUND");
                        assertEquals(1, fixture.count("tenant"));
                        assertEquals(1, fixture.count("tenant_member"));
                        assertEquals(1, fixture.count("tenant_policy"));
                        assertEquals(0, fixture.count("workspace"));
                        assertEquals(0, fixture.count("workspace_preference"));
                        assertEquals(rules, fixture.policies.findTenantPolicy(tenant).orElseThrow().rules());
                        assertNull(fixture.policies.findTenantPolicy(tenant).orElseThrow().personalTemplateVersion());
                        assertTrue(fixture.jdbc.queryForObject("SELECT event FROM arte_platform_outbox", String.class)
                                .contains("organizationTemplateVersion"));

                        String target = fixture.json.write(java.util.Map.of("tenantId", tenant));
                        String rename = fixture.json.write(java.util.Map.of("tenantId", tenant, "expectedTenantVersion", 0, "name", "Team Two"));
                        assertFalse(http.ok(root + "getTenant", target, null, alice).getJSONObject("data").getJSONArray("actions").contains("tenant:update"));
                        http.rejected(root + "updateTenant", rename, "without-update", alice, 403, "ACCESS_DENIED");
                        fixture.jdbc.update("INSERT INTO arte_rbac_relation VALUES('alice','user_to_operation','EnvironmentTenants:update')");
                        assertTrue(http.ok(root + "getTenant", target, null, alice).getJSONObject("data").getJSONArray("actions").contains("tenant:update"));
                        cn.hutool.json.JSONObject updated = http.ok(root + "updateTenant", rename, "rename", alice).getJSONObject("data");
                        assertEquals(1L, updated.getLong("resultVersion"));
                        assertEquals("Team Two", updated.getJSONObject("value").getStr("name"));
                        http.rejected(root + "updateTenant", rename, "stale-rename", alice, 409, "VERSION_CONFLICT");
                        assertEquals(updated.getStr("operationId"), http.ok(root + "updateTenant", rename, "rename", alice).getJSONObject("data").getStr("operationId"));
                        assertEquals(2, fixture.count("command"));
                        assertEquals(2, fixture.count("audit"));
                        assertEquals(2, fixture.count("outbox"));

                        com.arte.app.platform.workspace.ReactiveTenantQueries tenantApi =
                                c.getBean(com.arte.app.platform.workspace.ReactiveTenantQueries.class);
                        Tuple2<Signal<CommandReceipt<WorkspaceViews.TenantView>>, Signal<CommandReceipt<WorkspaceViews.TenantView>>> races = Mono.zip(
                                tenantApi.updateTenant(alice, new com.arte.core.pojo.workspace.TenantCommands.UpdateTenant(
                                        meta("race-one"), tenant, 1, "Concurrent One")).materialize(),
                                tenantApi.updateTenant(alice, new com.arte.core.pojo.workspace.TenantCommands.UpdateTenant(
                                        meta("race-two"), tenant, 1, "Concurrent Two")).materialize()).block(WAIT);
                        assertTrue(races.getT1().isOnNext() ^ races.getT2().isOnNext());
                        Throwable loser = races.getT1().isOnError() ? races.getT1().getThrowable() : races.getT2().getThrowable();
                        assertEquals(PlatformErrorCodeEnum.VERSION_CONFLICT, assertInstanceOf(PlatformException.class, loser).getCode());
                        assertEquals(2L, fixture.tenants.find(tenant).orElseThrow().version());

                        Scope personal = workspaces.initializePersonal(alice, meta("personal")).block(WAIT).value().workspace().scope();
                        String personalUpdate = fixture.json.write(java.util.Map.of("tenantId", personal.tenantId(),
                                "expectedTenantVersion", 0, "name", "Personal label"));
                        http.rejected(root + "updateTenant", fixture.json.write(java.util.Map.of("tenantId", personal.tenantId(),
                                "expectedTenantVersion", 0, "name", "Personal label", "type", 1)), "forged-conversion", alice, 400, "INVALID_ARGUMENT");
                        assertEquals(0, http.ok(root + "updateTenant", personalUpdate, "personal-label", alice)
                                .getJSONObject("data").getJSONObject("value").getInt("type"));
                        assertEquals(personal, workspaces.getPreference(alice).block(WAIT).defaultScope());

                        fixture.jdbc.update("DELETE FROM arte_rbac_relation WHERE target IN ('EnvironmentTenants:update','EnvironmentTenants:create')");
                        String renameReceipt = fixture.json.write(java.util.Map.of("operationId", updated.getStr("operationId")));
                        http.rejected(root + "getWorkspaceCommandReceipt", renameReceipt, null, alice, 403, "ACCESS_DENIED");
                        http.rejected(root + "updateTenant", rename, "rename", alice, 403, "ACCESS_DENIED");
                        http.rejected(root + "getWorkspaceCommandReceipt", createReceipt, null, alice, 403, "ACCESS_DENIED");
                    }
                });
    }

    @Test
    void enablingOrganizationCreationWithoutAnOrganizationTemplateLeavesCreationClosed() {
        context().withPropertyValues("arte.platform.tenant-governance.organization-creation-enabled=true").run(c -> {
            assertNull(c.getStartupFailure());
            com.arte.app.platform.workspace.ReactiveTenantQueries tenants = c.getBean(com.arte.app.platform.workspace.ReactiveTenantQueries.class);
            Authentication alice = login(1, "alice");
            assertFalse(tenants.creationOptions(alice).block(WAIT).available());
            PlatformException failure = assertThrows(PlatformException.class, () -> tenants.createOrganization(alice,
                    new com.arte.core.pojo.workspace.TenantCommands.CreateOrganization(meta("unconfigured"), "Org")).block(WAIT));
            assertEquals(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED, failure.getCode());
            assertEquals(0, fixture.count("tenant"));
            assertEquals(0, fixture.count("command"));
        });
    }

    @Test
    void membershipInvitationsSeparateScopesAndRequireExplicitAcceptance() throws Exception {
        membershipContext().run(c -> {
            assertNull(c.getStartupFailure());
            Authentication alice = login(1, "alice");
            Authentication bob = login(2, "bob");
            Authentication carol = login(3, "carol");
            try (ManagementHttpHarness http = membershipHttp(c)) {
                String tenant = command(http, "createOrganization", "org", alice, Map.of("name", "Team")).getStr("id");
                Scope first = createSpace(http, tenant, alice, "First");
                Scope other = createSpace(http, tenant, alice, "Other");
                String bobId = memberTarget(http, alice, tenant, "bob");
                String carolId = memberTarget(http, alice, tenant, "carol");
                JSONObject tenantInvite = command(http, "inviteTenantMember", "tenant-invite", alice,
                        Map.of("tenantId", tenant, "targetSubjectId", bobId, "expectedTenantVersion", fixture.tenants.find(tenant).orElseThrow().version()));
                Map<String, Object> accept = acceptance(tenantInvite);
                http.rejected("/workspace/acceptTenantInvitation", fixture.json.write(accept), "wrong-user", carol, 404, "NOT_FOUND");
                assertEquals(0, readMembership(http, "listMyTenantInvitations", carol, Map.of()).getInt("total"));
                JSONObject inboxItem = readMembership(http, "listMyTenantInvitations", bob, Map.of())
                        .getJSONArray("records").getJSONObject(0);
                assertEquals("Team", inboxItem.getStr("targetName"));
                assertNotNull(java.time.Instant.parse(inboxItem.getStr("expiresAt")));
                http.rejected("/workspace/findInvitationTarget", fixture.json.write(Map.of("tenantId", tenant, "account", "bo")),
                        null, alice, 404, "NOT_FOUND");
                JSONObject joined = membershipResponse(http, "acceptTenantInvitation", "tenant-join", bob, accept);
                assertEquals(2, joined.getJSONObject("value").getJSONObject("member").getInt("role"));
                assertEquals(1, membershipResponse(http, "acceptTenantInvitation", "tenant-join", bob, accept).getInt("delivery"));
                assertEquals(1, readMembership(http, "getWorkspaceCommandReceipt", bob,
                        Map.of("operationId", joined.getStr("operationId"))).getInt("delivery"));
                assertTrue(fixture.workspaceMembers.find(first, bobId).isEmpty());
                http.rejected("/workspace/listTenantMembers", fixture.json.write(Map.of("tenantId", tenant)),
                        null, bob, 403, "ACCESS_DENIED");

                JSONObject guestInvite = command(http, "inviteWorkspaceMember", "guest-invite", alice,
                        Map.of("scope", first, "targetSubjectId", carolId, "expectedWorkspaceVersion", 0, "role", 3));
                assertEquals("First", readMembership(http, "listMyWorkspaceInvitations", carol, Map.of())
                        .getJSONArray("records").getJSONObject(0).getStr("targetName"));
                JSONObject guest = command(http, "acceptWorkspaceInvitation", "guest-join", carol, acceptance(guestInvite));
                assertEquals(3, guest.getJSONObject("member").getInt("role"));
                assertEquals(TenantRoleEnum.GUEST, fixture.tenantMembers.find(tenant, carolId).orElseThrow().role());
                http.rejected("/workspace/getWorkspace", fixture.json.write(Map.of("scope", other)), null, carol, 404, "NOT_FOUND");
                http.rejected("/workspace/findInvitationTarget", fixture.json.write(Map.of("scope", first, "tenantId", tenant, "account", "bob")),
                        null, carol, 403, "ACCESS_DENIED");

                Map<String, Object> leave = Map.of("scope", first, "expectedWorkspaceVersion", 1, "expectedMemberVersion", 0);
                JSONObject left = membershipResponse(http, "leaveWorkspace", "leave", carol, leave);
                assertEquals(1, readMembership(http, "getWorkspaceCommandReceipt", carol,
                        Map.of("operationId", left.getStr("operationId"))).getInt("delivery"));
                http.rejected("/workspace/acceptWorkspaceInvitation", fixture.json.write(acceptance(guestInvite)),
                        "old-invite-new-key", carol, 409, "VERSION_CONFLICT");
                JSONObject fresh = command(http, "inviteWorkspaceMember", "guest-reinvite", alice,
                        Map.of("scope", first, "targetSubjectId", carolId, "expectedWorkspaceVersion", 2, "role", 2));
                command(http, "acceptWorkspaceInvitation", "guest-rejoin", carol, acceptance(fresh));
                assertEquals(1L, fixture.workspaceMembers.find(first, carolId).orElseThrow().generation());
                http.rejected("/workspace/getWorkspaceCommandReceipt", fixture.json.write(Map.of("operationId", left.getStr("operationId"))),
                        null, carol, 403, "ACCESS_DENIED");

                long tenantVersion = fixture.tenants.find(tenant).orElseThrow().version();
                http.rejected("/workspace/inviteTenantMember", fixture.json.write(Map.of("tenantId", tenant,
                                "targetSubjectId", bobId, "expectedTenantVersion", tenantVersion)),
                        "existing-member-invite", alice, 409, "ALREADY_MEMBER");
                assertEquals(tenantVersion, fixture.tenants.find(tenant).orElseThrow().version());
                // 升级前可能留有同目标的历史待接受邀请；接受仍保留已有成员角色及代次。
                fixture.jdbc.update("INSERT INTO arte_platform_membership_invitation"
                        + " (id,tenant_id,workspace_id,target_subject_id,issued_by,role,state,expires_at,accepted_member_id,version)"
                        + " SELECT 'legacy-pending',tenant_id,workspace_id,target_subject_id,issued_by,role,0,expires_at,NULL,0"
                        + " FROM arte_platform_membership_invitation WHERE id=?", tenantInvite.getStr("invitationId"));
                JSONObject already = command(http, "acceptTenantInvitation", "legacy-existing-join", bob,
                        Map.of("invitationId", "legacy-pending", "expectedInvitationVersion", 0));
                assertEquals(1, already.getInt("outcome"));
                assertEquals(joined.getJSONObject("value").getJSONObject("member"), already.getJSONObject("member"));
                assertEquals(tenantVersion, fixture.tenants.find(tenant).orElseThrow().version());

                long workspaceVersion = fixture.workspaces.find(other).orElseThrow().version();
                JSONObject expired = command(http, "inviteWorkspaceMember", "expiry-fixture", alice,
                        Map.of("scope", other, "targetSubjectId", carolId, "expectedWorkspaceVersion", workspaceVersion, "role", 2));
                fixture.jdbc.update("UPDATE arte_platform_membership_invitation SET expires_at=? WHERE id=?",
                        LocalDateTime.of(2020, 1, 1, 0, 0), expired.getStr("invitationId"));
                assertTrue(readMembership(http, "listMyWorkspaceInvitations", carol, Map.of()).getJSONArray("records")
                        .stream().map(value -> (JSONObject) value)
                        .anyMatch(row -> expired.getStr("invitationId").equals(row.getStr("invitationId")) && row.getInt("state") == 3));
                http.rejected("/workspace/acceptWorkspaceInvitation", fixture.json.write(acceptance(expired)),
                        "expired-join", carol, 409, "VERSION_CONFLICT");
                assertTrue(fixture.workspaceMembers.find(other, carolId).isEmpty());

                Scope personal = c.getBean(ReactiveWorkspaceOperations.class).initializePersonal(alice, meta("personal"))
                        .block(WAIT).value().workspace().scope();
                JSONObject personalCaps = readMembership(http, "getMembershipCapabilities", alice,
                        Map.of("tenantId", personal.tenantId(), "scope", personal));
                assertFalse(personalCaps.getBool("transferOwner"));
                assertFalse(personalCaps.getBool("leave"));
                http.rejected("/workspace/inviteTenantMember", fixture.json.write(Map.of("tenantId", personal.tenantId(),
                        "targetSubjectId", bobId, "expectedTenantVersion", 0)), "personal-tenant-invite", alice, 403, "ACCESS_DENIED");
                JSONObject collaboration = command(http, "inviteWorkspaceMember", "personal-space-invite", alice,
                        Map.of("scope", personal, "targetSubjectId", bobId, "expectedWorkspaceVersion", 0, "role", 2));
                JSONObject collaborator = command(http, "acceptWorkspaceInvitation", "personal-space-join", bob,
                        acceptance(collaboration)).getJSONObject("member");
                http.rejected("/workspace/transferWorkspaceOwner", fixture.json.write(Map.of("scope", personal,
                                "targetMemberId", collaborator.getStr("memberId"), "expectedWorkspaceVersion", 1,
                                "expectedOwnerMemberVersion", personalCaps.getLong("memberVersion"), "expectedTargetMemberVersion", 0)),
                        "personal-default-transfer", alice, 403, "POLICY_DENIED");
                http.rejected("/workspace/leaveWorkspace", fixture.json.write(Map.of("scope", personal,
                                "expectedWorkspaceVersion", 1, "expectedMemberVersion", personalCaps.getLong("memberVersion"))),
                        "personal-default-leave", alice, 403, "ACCESS_DENIED");
            }
        });
    }

    @Test
    void membershipHandoffAndTenantRemovalProtectOwnersAndInvalidateDependentAccess() throws Exception {
        membershipContext().run(c -> {
            assertNull(c.getStartupFailure());
            Authentication alice = login(1, "alice");
            Authentication bob = login(2, "bob");
            Authentication carol = login(3, "carol");
            try (ManagementHttpHarness http = membershipHttp(c)) {
                String tenant = command(http, "createOrganization", "org", alice, Map.of("name", "Team")).getStr("id");
                Scope space = createSpace(http, tenant, alice, "Team Space");
                String bobId = memberTarget(http, alice, tenant, "bob");
                String carolId = memberTarget(http, alice, tenant, "carol");
                JSONObject tenantInvite = command(http, "inviteTenantMember", "tenant-invite", alice,
                        Map.of("tenantId", tenant, "targetSubjectId", bobId, "expectedTenantVersion", fixture.tenants.find(tenant).orElseThrow().version()));
                JSONObject member = command(http, "acceptTenantInvitation", "tenant-join", bob, acceptance(tenantInvite))
                        .getJSONObject("member");
                JSONObject invite = command(http, "inviteWorkspaceMember", "workspace-invite", alice,
                        Map.of("scope", space, "targetSubjectId", bobId, "expectedWorkspaceVersion", 0, "role", 2));
                JSONObject spaceMember = command(http, "acceptWorkspaceInvitation", "workspace-join", bob, acceptance(invite))
                        .getJSONObject("member");

                JSONObject caps = readMembership(http, "getMembershipCapabilities", alice, Map.of("tenantId", tenant, "scope", space));
                Map<String, Object> transfer = Map.of("scope", space, "targetMemberId", spaceMember.getStr("memberId"),
                        "expectedWorkspaceVersion", 1, "expectedOwnerMemberVersion", caps.getLong("memberVersion"), "expectedTargetMemberVersion", 0);
                http.rejected("/workspace/transferWorkspaceOwner", fixture.json.write(transfer), "stale-transfer", bob, 403, "ACCESS_DENIED");
                assertEquals(1, command(http, "transferWorkspaceOwner", "space-transfer", alice, transfer).getInt("currentRole"));
                assertEquals(WorkspaceRoleEnum.OWNER, fixture.workspaceMembers.find(space, bobId).orElseThrow().role());
                assertEquals(WorkspaceRoleEnum.ADMIN, fixture.workspaceMembers.find(space, fixture.actor.principal().subjectId()).orElseThrow().role());

                JSONObject tenantCaps = readMembership(http, "getMembershipCapabilities", alice, Map.of("tenantId", tenant));
                Map<String, Object> remove = Map.of("tenantId", tenant, "memberId", member.getStr("memberId"),
                        "expectedTenantVersion", 2, "expectedMemberVersion", 0);
                http.rejected("/workspace/removeTenantMember", fixture.json.write(remove), "protected-owner", alice, 403, "ACCESS_DENIED");
                JSONObject pending = command(http, "inviteWorkspaceMember", "pending", bob,
                        Map.of("scope", space, "targetSubjectId", carolId, "expectedWorkspaceVersion", 2, "role", 2));
                JSONObject aliceSpace = readMembership(http, "getMembershipCapabilities", alice, Map.of("tenantId", tenant, "scope", space));
                command(http, "transferWorkspaceOwner", "space-return", bob, Map.of("scope", space, "targetMemberId", aliceSpace.getStr("memberId"),
                        "expectedWorkspaceVersion", 2, "expectedOwnerMemberVersion", 1, "expectedTargetMemberVersion", 1));
                membershipResponse(http, "removeTenantMember", "tenant-remove", alice, remove);
                assertEquals(MembershipStateEnum.REVOKED, fixture.workspaceMembers.find(space, bobId).orElseThrow().state());
                http.rejected("/workspace/getWorkspace", fixture.json.write(Map.of("scope", space)), null, bob, 404, "NOT_FOUND");
                http.rejected("/workspace/acceptWorkspaceInvitation", fixture.json.write(acceptance(pending)),
                        "issuer-revoked", carol, 404, "NOT_FOUND");
                JSONObject reinvite = command(http, "inviteTenantMember", "tenant-reinvite", alice,
                        Map.of("tenantId", tenant, "targetSubjectId", bobId, "expectedTenantVersion", 3));
                JSONObject newMember = command(http, "acceptTenantInvitation", "tenant-rejoin", bob, acceptance(reinvite)).getJSONObject("member");
                assertEquals(1L, fixture.tenantMembers.find(tenant, bobId).orElseThrow().generation());
                assertEquals(MembershipStateEnum.REVOKED, fixture.workspaceMembers.find(space, bobId).orElseThrow().state());
                assertEquals(1, command(http, "transferTenantOwner", "tenant-transfer", alice, Map.of("tenantId", tenant,
                        "targetMemberId", newMember.getStr("memberId"), "expectedTenantVersion", 4,
                        "expectedOwnerMemberVersion", tenantCaps.getLong("memberVersion"),
                        "expectedTargetMemberVersion", newMember.getLong("version"))).getInt("currentRole"));
                assertEquals(TenantRoleEnum.OWNER, fixture.tenantMembers.find(tenant, bobId).orElseThrow().role());
                assertEquals(TenantRoleEnum.ADMIN, fixture.tenantMembers.find(tenant, fixture.actor.principal().subjectId()).orElseThrow().role());

                JSONObject revoked = command(http, "inviteTenantMember", "new-pending", bob,
                        Map.of("tenantId", tenant, "targetSubjectId", carolId, "expectedTenantVersion", 5));
                command(http, "revokeTenantInvitation", "revoke", bob, Map.of("tenantId", tenant, "expectedTenantVersion", 5,
                        "invitationId", revoked.getStr("invitationId"), "expectedInvitationVersion", 0));
                http.rejected("/workspace/acceptTenantInvitation", fixture.json.write(acceptance(revoked)),
                        "accept-revoked", carol, 409, "VERSION_CONFLICT");

                JSONObject formerOwner = readMembership(http, "getMembershipCapabilities", alice, Map.of("tenantId", tenant));
                Map<String, Object> demote = Map.of("tenantId", tenant, "memberId", formerOwner.getStr("memberId"), "role", 2,
                        "expectedTenantVersion", 5, "expectedMemberVersion", formerOwner.getLong("memberVersion"));
                JSONObject changed = membershipResponse(http, "changeTenantMemberRole", "demote", bob, demote);
                assertEquals(2, changed.getJSONObject("value").getInt("role"));
                assertEquals(1, readMembership(http, "getWorkspaceCommandReceipt", bob,
                        Map.of("operationId", changed.getStr("operationId"))).getInt("delivery"));
                http.rejected("/workspace/changeTenantMemberRole", fixture.json.write(demote),
                        "stale-role", bob, 409, "VERSION_CONFLICT");
                assertFalse(readMembership(http, "getMembershipCapabilities", alice, Map.of("tenantId", tenant)).getBool("manage"));
                http.rejected("/workspace/listTenantMembers", fixture.json.write(Map.of("tenantId", tenant)),
                        null, alice, 403, "ACCESS_DENIED");
            }
        });
    }

    @Test
    void invitationIdentificationDeclineCountsAndDuplicateGuards() throws Exception {
        membershipContext().run(c -> {
            assertNull(c.getStartupFailure());
            Authentication alice = login(1, "alice");
            Authentication bob = login(2, "bob");
            Authentication carol = login(3, "carol");
            try (ManagementHttpHarness http = membershipHttp(c)) {
                String tenant = command(http, "createOrganization", "org", alice, Map.of("name", "Team")).getStr("id");
                Scope scope = createSpace(http, tenant, alice, "Shared");
                String bobId = memberTarget(http, alice, tenant, "bob");
                Map<String, Object> issue = Map.of("scope", scope, "targetSubjectId", bobId,
                        "expectedWorkspaceVersion", 0, "role", 2);
                JSONObject invitation = command(http, "inviteWorkspaceMember", "invite", alice, issue);
                http.rejected("/workspace/inviteWorkspaceMember", fixture.json.write(issue), "duplicate", alice, 409, "INVITATION_PENDING");
                JSONObject counts = readMembership(http, "getMyPendingInvitationCounts", bob, Map.of());
                assertEquals(1, counts.getInt("workspace"));
                assertEquals(0, counts.getInt("tenant"));
                assertEquals(0, readMembership(http, "getMyPendingInvitationCounts", carol, Map.of()).getInt("workspace"));
                JSONObject row = readMembership(http, "listMyWorkspaceInvitations", bob, Map.of("state", 0))
                        .getJSONArray("records").getJSONObject(0);
                assertEquals("Team", row.getStr("tenantName"));
                assertEquals(1, row.getInt("tenantType"));
                assertEquals("alice", row.getStr("inviterDisplayName"));
                assertTrue(row.getStr("sharingBoundary").contains("私有"));
                http.rejected("/workspace/declineWorkspaceInvitation", fixture.json.write(acceptance(invitation)),
                        "wrong-user", carol, 404, "NOT_FOUND");
                JSONObject declined = membershipResponse(http, "declineWorkspaceInvitation", "decline", bob, acceptance(invitation));
                assertEquals(4, declined.getJSONObject("value").getInt("state"));
                assertEquals(1, membershipResponse(http, "declineWorkspaceInvitation", "decline", bob, acceptance(invitation)).getInt("delivery"));
                assertEquals(1, readMembership(http, "getWorkspaceCommandReceipt", bob,
                        Map.of("operationId", declined.getStr("operationId"))).getInt("delivery"));
                assertTrue(fixture.tenantMembers.find(tenant, bobId).isEmpty());
                assertTrue(fixture.workspaceMembers.find(scope, bobId).isEmpty());
                assertEquals(0, readMembership(http, "getMyPendingInvitationCounts", bob, Map.of()).getInt("workspace"));
                assertEquals(1, readMembership(http, "listMyWorkspaceInvitations", bob, Map.of("state", 4)).getInt("total"));
                assertEquals(0, readMembership(http, "listMyWorkspaceInvitations", bob, Map.of("state", 0)).getInt("total"));
                http.rejected("/workspace/acceptWorkspaceInvitation", fixture.json.write(acceptance(invitation)),
                        "old-accept", bob, 409, "VERSION_CONFLICT");
                JSONObject fresh = command(http, "inviteWorkspaceMember", "fresh", alice, issue);
                command(http, "acceptWorkspaceInvitation", "join", bob, acceptance(fresh));
                Map<String, Object> existing = new HashMap<>(issue);
                existing.put("expectedWorkspaceVersion", 1);
                http.rejected("/workspace/inviteWorkspaceMember", fixture.json.write(existing), "already", alice, 409, "ALREADY_MEMBER");
                JSONObject origin = readMembership(http, "getWorkspace", bob, Map.of("scope", scope)).getJSONObject("identity");
                assertEquals("Team", origin.getStr("tenantName"));
                assertFalse(origin.getBool("createdByMe"));
                JSONObject directory = readMembership(http, "browseAccessibleWorkspaces", bob, Map.of("size", 20));
                assertEquals("Team", directory.getJSONArray("records").getJSONObject(0).getJSONObject("identity").getStr("tenantName"));

                JSONObject orgInvite = command(http, "inviteTenantMember", "org-invite", alice, Map.of("tenantId", tenant,
                        "targetSubjectId", bobId, "expectedTenantVersion", fixture.tenants.find(tenant).orElseThrow().version()));
                JSONObject orgDecline = command(http, "declineTenantInvitation", "org-decline", bob, acceptance(orgInvite));
                assertEquals(4, orgDecline.getInt("state"));
                assertEquals(TenantRoleEnum.GUEST, fixture.tenantMembers.find(tenant, bobId).orElseThrow().role());

                Scope second = createSpace(http, tenant, alice, "Expired");
                JSONObject expired = command(http, "inviteWorkspaceMember", "expired", alice,
                        Map.of("scope", second, "targetSubjectId", bobId, "expectedWorkspaceVersion", 0, "role", 3));
                fixture.jdbc.update("UPDATE arte_platform_membership_invitation SET expires_at=? WHERE id=?",
                        LocalDateTime.of(2020, 1, 1, 0, 0), expired.getStr("invitationId"));
                assertEquals(0, readMembership(http, "getMyPendingInvitationCounts", bob, Map.of()).getInt("workspace"));
                assertEquals(1, readMembership(http, "listMyWorkspaceInvitations", bob, Map.of("state", 3)).getInt("total"));
                assertEquals(0, readMembership(http, "listWorkspaceInvitations", alice, Map.of("scope", second, "state", 0)).getInt("total"));
                http.rejected("/workspace/declineWorkspaceInvitation", fixture.json.write(acceptance(expired)),
                        "expired-decline", bob, 409, "VERSION_CONFLICT");
                JSONObject renewed = command(http, "inviteWorkspaceMember", "renew", alice,
                        Map.of("scope", second, "targetSubjectId", bobId, "expectedWorkspaceVersion", 0, "role", 3));
                assertNotEquals(expired.getStr("invitationId"), renewed.getStr("invitationId"));
                assertEquals(1, readMembership(http, "getMyPendingInvitationCounts", bob, Map.of()).getInt("workspace"));

                Scope personal = c.getBean(ReactiveWorkspaceOperations.class).initializePersonal(alice, meta("personal")).block(WAIT).value().workspace().scope();
                JSONObject personalInvite = command(http, "inviteWorkspaceMember", "personal-invite", alice,
                        Map.of("scope", personal, "targetSubjectId", bobId, "expectedWorkspaceVersion", 0, "role", 3));
                JSONObject personalRow = readMembership(http, "listMyWorkspaceInvitations", bob, Map.of("state", 0))
                        .getJSONArray("records").stream().map(v -> (JSONObject) v)
                        .filter(v -> personalInvite.getStr("invitationId").equals(v.getStr("invitationId"))).findFirst().orElseThrow();
                assertEquals(0, personalRow.getInt("tenantType"));
                assertEquals("alice", personalRow.getStr("hostDisplayName"));
            }
        });
    }

    @Test
    void concurrentDifferentKeysCannotCreateDuplicateInvitationsAndOnlyOneDecisionWins() throws Exception {
        membershipContext().run(c -> {
            Authentication alice = login(1, "alice");
            Authentication bob = login(2, "bob");
            try (ManagementHttpHarness http = membershipHttp(c)) {
                String tenant = command(http, "createOrganization", "org", alice, Map.of("name", "Concurrent Team")).getStr("id");
                Scope scope = createSpace(http, tenant, alice, "Concurrent");
                String bobId = memberTarget(http, alice, tenant, "bob");
                var identities = c.getBean(com.arte.app.platform.identity.RbacIdentityProvider.class);
                var owner = identities.actor(alice);
                var target = identities.actor(bob);
                var service = c.getBean(DefaultWorkspaceServices.class).membership();
                var scheduler = reactor.core.scheduler.Schedulers.boundedElastic();
                var a = Mono.fromCallable(() -> service.invite(owner, new WorkspaceCommands.InviteMember(meta("race-a"),
                        scope, 0, bobId, WorkspaceRoleEnum.MEMBER, null))).subscribeOn(scheduler).materialize();
                var b = Mono.fromCallable(() -> service.invite(owner, new WorkspaceCommands.InviteMember(meta("race-b"),
                        scope, 0, bobId, WorkspaceRoleEnum.GUEST, null))).subscribeOn(scheduler).materialize();
                var issued = Mono.zip(a, b).block(WAIT);
                assertEquals(1, (issued.getT1().isOnNext() ? 1 : 0) + (issued.getT2().isOnNext() ? 1 : 0));
                var failure = issued.getT1().isOnError() ? issued.getT1().getThrowable() : issued.getT2().getThrowable();
                assertEquals(PlatformErrorCodeEnum.INVITATION_PENDING, ((PlatformException) failure).getCode());
                var invitation = (issued.getT1().isOnNext() ? issued.getT1().get() : issued.getT2().get()).value();
                var accept = Mono.fromCallable(() -> service.accept(target, new WorkspaceCommands.AcceptInvitation(
                        meta("accept-race"), invitation.invitationId(), 0))).subscribeOn(scheduler).materialize();
                var decline = Mono.fromCallable(() -> service.decline(target, new WorkspaceCommands.DeclineInvitation(
                        meta("decline-race"), invitation.invitationId(), 0))).subscribeOn(scheduler).materialize();
                var decisions = Mono.zip(accept, decline).block(WAIT);
                assertEquals(1, (decisions.getT1().isOnNext() ? 1 : 0) + (decisions.getT2().isOnNext() ? 1 : 0));
                assertEquals(decisions.getT1().isOnNext(), fixture.workspaceMembers.find(scope, bobId).isPresent());
                assertEquals(0, service.pendingInvitationCounts(target).workspace());
            }
        });
    }

    @Test
    void lifecycleArchivesRestoresAndAllowsRevocationWithoutRoleExpansion() throws Exception {
        fixture.installDdl(java.nio.file.Path.of("scripts/arte-platform-workspace-lifecycle-ddl-mysql.sql"));
        membershipContext().run(c -> {
            Authentication alice = login(1, "alice"), bob = login(2, "bob");
            try (ManagementHttpHarness http = membershipHttp(c)) {
                String tenant = command(http, "createOrganization", "org", alice, Map.of("name", "Archive Team")).getStr("id");
                Scope scope = createSpace(http, tenant, alice, "Archive");
                String target = memberTarget(http, alice, tenant, "bob");
                JSONObject invite = command(http, "inviteWorkspaceMember", "invite", alice,
                        Map.of("scope", scope, "expectedWorkspaceVersion", 0, "targetSubjectId", target, "role", 2));
                JSONObject joined = command(http, "acceptWorkspaceInvitation", "accept", bob, acceptance(invite)).getJSONObject("member");
                JSONObject archived = membershipResponse(http, "archiveWorkspace", "archive", alice,
                        Map.of("scope", scope, "expectedWorkspaceVersion", 1, "reason", "done"));
                assertEquals(1, archived.getJSONObject("value").getInt("state"));
                assertEquals(2, archived.getJSONObject("value").getLong("version"));
                assertTrue(readMembership(http, "getMembershipCapabilities", alice, Map.of("tenantId", tenant, "scope", scope)).getBool("readOnly"));
                assertFalse(readMembership(http, "getMembershipCapabilities", alice, Map.of("tenantId", tenant, "scope", scope)).getBool("invite"));
                assertEquals("Archive", readMembership(http, "getWorkspace", bob, Map.of("scope", scope)).getStr("name"));
                http.rejected("/workspace/renameWorkspace", fixture.json.write(Map.of("scope", scope, "expectedVersion", 2, "name", "bad")),
                        "rename", alice, 409, "SPACE_READ_ONLY");
                http.rejected("/workspace/changeWorkspaceMemberRole", fixture.json.write(Map.of("scope", scope, "expectedWorkspaceVersion", 2,
                        "memberId", joined.getStr("memberId"), "expectedMemberVersion", 0, "role", 1)), "promote", alice, 403, "ACCESS_DENIED");
                command(http, "leaveWorkspace", "leave", bob, Map.of("scope", scope, "expectedWorkspaceVersion", 2, "expectedMemberVersion", 0));
                JSONObject restored = command(http, "unarchiveWorkspace", "restore", alice, Map.of("scope", scope, "expectedWorkspaceVersion", 3));
                assertEquals(0, restored.getInt("state"));
                assertEquals(1, readMembership(http, "getWorkspaceCommandReceipt", alice, Map.of("operationId", archived.getStr("operationId"))).getInt("delivery"));
                Scope personal = c.getBean(ReactiveWorkspaceOperations.class).initializePersonal(alice, meta("personal")).block(WAIT).value().workspace().scope();
                http.rejected("/workspace/archiveWorkspace", fixture.json.write(Map.of("scope", personal, "expectedWorkspaceVersion", 0)), "default-archive", alice, 403, "POLICY_DENIED");
                http.rejected("/workspace/closeWorkspace", fixture.json.write(Map.of("scope", personal, "expectedWorkspaceVersion", 0)), "default-close", alice, 403, "POLICY_DENIED");
            }
        });
    }

    @Test
    void durableClosureWaitsForEvidenceSurvivesRestartAndPreservesResources() throws Exception {
        fixture.installDdl(java.nio.file.Path.of("scripts/arte-platform-workspace-lifecycle-ddl-mysql.sql"));
        var waiting = new java.util.concurrent.atomic.AtomicBoolean(true);
        com.arte.core.spi.workspace.WorkspaceClosureInspector readiness = new com.arte.core.spi.workspace.WorkspaceClosureInspector() {
            public String domain() {
                return "test-domain";
            }

            public Inspection inspect(Scope scope) {
                return new Inspection(waiting.get()
                        ? List.of(new com.arte.core.pojo.workspace.ClosureBlocker(domain(), "retention", "EXECUTION_UNKNOWN", true)) : List.of(), Map.of("records", 1L));
            }
        };
        membershipContext().withBean("closureTestInspector", com.arte.core.spi.workspace.WorkspaceClosureInspector.class, () -> readiness).run(c -> {
            Authentication alice = login(1, "alice"), bob = login(2, "bob");
            try (ManagementHttpHarness http = membershipHttp(c)) {
                String tenant = command(http, "createOrganization", "org", alice, Map.of("name", "Close Team")).getStr("id");
                Scope scope = createSpace(http, tenant, alice, "Close");
                assertEquals(1, readMembership(http, "previewWorkspaceClosure", alice, Map.of("scope", scope)).getJSONArray("blockers").size());
                JSONObject accepted = membershipResponse(http, "closeWorkspace", "close", alice, Map.of("scope", scope, "expectedWorkspaceVersion", 0));
                String operation = accepted.getStr("operationId");
                assertEquals(2, fixture.workspaces.find(scope).orElseThrow().state().getValue());
                assertEquals(0, readMembership(http, "browseAccessibleWorkspaces", alice, Map.of()).getJSONArray("records").size());
                assertEquals(1, readMembership(http, "listWorkspaceClosures", alice, Map.of()).getInt("total"));
                assertEquals(0, readMembership(http, "listWorkspaceClosures", bob, Map.of()).getInt("total"));
                http.rejected("/workspace/getWorkspaceClosureProgress", fixture.json.write(Map.of("scope", scope, "operationId", operation)), null, bob, 404, "NOT_FOUND");
                var lifecycle = c.getBean(com.arte.core.application.workspace.DefaultWorkspaceLifecycleService.class);
                lifecycle.advance(scope, operation);
                JSONObject progress = readMembership(http, "getWorkspaceClosureProgress", alice, Map.of("scope", scope, "operationId", operation));
                assertEquals(2, progress.getInt("state"));
                assertEquals("EXECUTION_UNKNOWN", progress.getJSONArray("blockers").getJSONObject(0).getStr("code"));
                assertEquals(1, membershipResponse(http, "closeWorkspace", "close", alice, Map.of("scope", scope, "expectedWorkspaceVersion", 0)).getInt("delivery"));
                waiting.set(false);
                fixture.jdbc.update("UPDATE arte_rbac_user SET status=0 WHERE id=1");
                // 独立服务图只读取相同数据库中的工作项，不依赖原进程内存或原请求仍在线。
                context().withBean("closureTestInspector", com.arte.core.spi.workspace.WorkspaceClosureInspector.class, () -> readiness).run(second -> {
                    second.getBean(com.arte.core.application.workspace.DefaultWorkspaceLifecycleService.class).advance(scope, operation);
                });
                assertEquals(3, fixture.workspaces.find(scope).orElseThrow().state().getValue());
                fixture.jdbc.update("UPDATE arte_rbac_user SET status=1 WHERE id=1");
                assertEquals(3, readMembership(http, "getWorkspaceClosureProgress", alice, Map.of("scope", scope, "operationId", operation)).getInt("state"));
                assertEquals(1, readMembership(http, "getWorkspaceCommandReceipt", alice, Map.of("operationId", operation)).getInt("delivery"));
                assertEquals(1, fixture.count("workspace"));
                assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_audit WHERE action='workspace:deleted'", Integer.class));
                lifecycle.advance(scope, operation);
                assertEquals(1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_audit WHERE action='workspace:deleted'", Integer.class));
            }
        });
    }

    @Test
    void expiredClosureLeaseCannotCommitAfterAnotherWorkerReclaimsIt() throws Exception {
        fixture.installDdl(java.nio.file.Path.of("scripts/arte-platform-workspace-lifecycle-ddl-mysql.sql"));
        membershipContext().run(c -> {
            Authentication alice = login(1, "alice");
            try (ManagementHttpHarness http = membershipHttp(c)) {
                String tenant = command(http, "createOrganization", "org", alice, Map.of("name", "Lease Team")).getStr("id");
                Scope scope = createSpace(http, tenant, alice, "Lease");
                String operation = membershipResponse(http, "closeWorkspace", "close", alice,
                        Map.of("scope", scope, "expectedWorkspaceVersion", 0)).getStr("operationId");
                var store = c.getBean(JdbcWorkspaceClosureStore.class);
                var tx = c.getBean("platformTransactionRunner", com.arte.core.spi.platform.PublicTransactionRunner.class);
                var now = java.time.Instant.parse("2026-11-10T00:00:00Z");
                var first = tx.required(() -> store.claim(scope, operation, "ai", "retention", 0, "worker-1", now, java.time.Duration.ofSeconds(30)).orElseThrow());
                assertTrue(tx.required(() -> store.claim(scope, operation, "ai", "retention", first.version(), "worker-2", now.plusSeconds(1), java.time.Duration.ofSeconds(30))).isEmpty());
                var second = tx.required(() -> store.claim(scope, operation, "ai", "retention", first.version(), "worker-2", now.plusSeconds(31), java.time.Duration.ofSeconds(30)).orElseThrow());
                assertTrue(second.fencing() > first.fencing());
                var cleared = new com.arte.core.pojo.workspace.ClosureWorkItem(operation, scope, "ai", "retention", second.targetRefs(),
                        com.arte.core.enums.workspace.ClosureWorkStateEnum.WAITING_EVIDENCE, second.version() + 1, null, null, null);
                // 即使版本恰好相同，过期 worker 的 fencing 仍必须被拒绝。
                assertFalse(tx.required(() -> store.replaceProgress(cleared, second.version(), first.fencing(), List.of())));
                assertTrue(tx.required(() -> store.replaceProgress(cleared, second.version(), second.fencing(), List.of())));
                var third = tx.required(() -> store.claim(scope, operation, "ai", "retention", cleared.version(), "worker-3", now.plusSeconds(32), java.time.Duration.ofSeconds(30)).orElseThrow());
                assertTrue(third.fencing() > second.fencing());
            }
        });
    }

    @Test
    void archiveAndCloseCompeteOnTheSameVersionWithoutPartialEffects() throws Exception {
        fixture.installDdl(java.nio.file.Path.of("scripts/arte-platform-workspace-lifecycle-ddl-mysql.sql"));
        membershipContext().run(c -> {
            Authentication alice = login(1, "alice");
            try (ManagementHttpHarness http = membershipHttp(c)) {
                String tenant = command(http, "createOrganization", "org", alice, Map.of("name", "Race Team")).getStr("id");
                Scope scope = createSpace(http, tenant, alice, "Lifecycle Race");
                var actor = c.getBean(com.arte.app.platform.identity.RbacIdentityProvider.class).actor(alice);
                var lifecycle = c.getBean(com.arte.core.application.workspace.DefaultWorkspaceLifecycleService.class);
                var scheduler = reactor.core.scheduler.Schedulers.boundedElastic();
                var a = Mono.fromCallable(() -> lifecycle.archive(actor, new WorkspaceCommands.ChangeWorkspaceState(meta("race-archive"), scope, 0, "done")))
                        .subscribeOn(scheduler).materialize();
                var b = Mono.fromCallable(() -> lifecycle.close(actor, new WorkspaceCommands.CloseWorkspace(meta("race-close"), scope, 0, "done")))
                        .subscribeOn(scheduler).materialize();
                var result = Mono.zip(a, b).block(WAIT);
                assertEquals(1, (result.getT1().isOnNext() ? 1 : 0) + (result.getT2().isOnNext() ? 1 : 0));
                var failure = result.getT1().isOnError() ? result.getT1().getThrowable() : result.getT2().getThrowable();
                assertEquals(PlatformErrorCodeEnum.VERSION_CONFLICT, ((PlatformException) failure).getCode());
                assertEquals(1, fixture.workspaces.find(scope).orElseThrow().version());
                assertEquals(result.getT2().isOnNext() ? 1 : 0, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_workspace_closure", Integer.class));
            }
        });
    }

    private ApplicationContextRunner membershipContext() throws Exception {
        fixture.jdbc.execute("CREATE TABLE arte_rbac_relation(source VARCHAR(256), binding_type VARCHAR(96), target VARCHAR(256))");
        fixture.jdbc.execute("CREATE TABLE arte_rbac_role(role_code VARCHAR(96), status VARCHAR(8))");
        fixture.jdbc.update("INSERT INTO arte_rbac_relation VALUES('alice','user_to_operation','EnvironmentTenants:create')");
        for (int id = 2; id <= 3; id++) {
            fixture.jdbc.update("INSERT INTO arte_rbac_user VALUES(?,?,1,?)", id, id == 2 ? "bob" : "carol",
                    java.sql.Timestamp.valueOf("2026-10-10 10:00:00"));
        }
        fixture.installDdl(java.nio.file.Path.of("scripts/arte-platform-membership-invitation-ddl-mysql.sql"));
        PolicyRules rules = new PolicyRules(Set.of(com.arte.core.constant.platform.PlatformResourceTypes.ARTICLE), Map.of(),
                false, false, Set.of(), Set.of(), Set.of(), Set.of(), Set.of(), Set.of());
        return context().withPropertyValues("arte.platform.tenant-governance.organization-creation-enabled=true",
                "arte.platform.tenant-governance.organization-policy-template=" +
                        fixture.json.write(new OrganizationTenantPolicyTemplate(7, rules, false, 1, 1)));
    }

    private ManagementHttpHarness membershipHttp(AssertableApplicationContext c) {
        return new ManagementHttpHarness(
                new TenantDirectoryController(c.getBean(com.arte.app.platform.workspace.ReactiveTenantQueries.class)),
                new WorkspaceController(c.getBean(ReactiveWorkspaceOperations.class)),
                c.getBean(MembershipGovernanceController.class),
                new com.arte.app.web.controller.platform.WorkspaceLifecycleController(
                        c.getBean(com.arte.core.application.workspace.DefaultWorkspaceLifecycleService.class),
                        c.getBean(com.arte.app.platform.identity.PlatformAuthenticatedExecutor.class)));
    }

    private String memberTarget(ManagementHttpHarness http, Authentication actor, String tenant, String account) {
        return readMembership(http, "findInvitationTarget", actor, Map.of("tenantId", tenant, "account", account)).getStr("subjectId");
    }

    private Scope createSpace(ManagementHttpHarness http, String tenant, Authentication actor, String name) {
        JSONObject scope = command(http, "createWorkspace", "create-" + name.replace(" ", "-"), actor, Map.of("tenantId", tenant, "name", name))
                .getJSONObject("scope");
        return new Scope(scope.getStr("tenantId"), scope.getStr("workspaceId"));
    }

    private static Map<String, Object> acceptance(JSONObject invitation) {
        return Map.of("invitationId", invitation.getStr("invitationId"), "expectedInvitationVersion", invitation.getLong("version"));
    }

    private JSONObject membershipResponse(ManagementHttpHarness http, String endpoint, String key, Authentication actor, Map<String, ?> body) {
        if (endpoint.equals("closeWorkspace")) {
            var response = http.execute("/workspace/" + endpoint, fixture.json.write(body), key, actor);
            String contents = ManagementHttpHarness.contents(response);
            assertEquals(202, response.getResponse().getStatus(), contents);
            var value = cn.hutool.json.JSONUtil.parseObj(contents);
            assertTrue(value.getBool("success"), contents);
            return value.getJSONObject("data");
        }
        return http.ok("/workspace/" + endpoint, fixture.json.write(body), key, actor).getJSONObject("data");
    }

    private JSONObject command(ManagementHttpHarness http, String endpoint, String key, Authentication actor, Map<String, ?> body) {
        return membershipResponse(http, endpoint, key, actor, body).getJSONObject("value");
    }

    private JSONObject readMembership(ManagementHttpHarness http, String endpoint, Authentication actor, Map<String, ?> body) {
        return membershipResponse(http, endpoint, null, actor, body);
    }

}
