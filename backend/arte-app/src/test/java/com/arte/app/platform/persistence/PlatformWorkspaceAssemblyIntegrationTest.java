package com.arte.app.platform.persistence;

import com.arte.app.platform.identity.PlatformIdentityConfiguration;
import com.arte.app.platform.identity.RbacAccountMapperConfiguration;
import com.arte.app.platform.identity.RbacAccountReader;
import com.arte.app.platform.workspace.PlatformWorkspaceConfiguration;
import com.arte.app.platform.workspace.ReactiveWorkspaceOperations;
import com.arte.app.pojo.rbac.JwtUserDto;
import com.arte.app.service.platform.AuditSinkServiceImpl;
import com.arte.core.application.workspace.DefaultWorkspaceServices;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.UserOnlineInfo;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.PageQuery;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

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
                        PlatformPersistenceConfiguration.class, Stores.class)
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
}
