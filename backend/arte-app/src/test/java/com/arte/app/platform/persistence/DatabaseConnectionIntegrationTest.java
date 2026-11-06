package com.arte.app.platform.persistence;

import com.arte.ainew.infrastructure.http.GenerationException;
import com.arte.ainew.infrastructure.http.HttpEgressPolicy;
import com.arte.app.platform.ai.DatabaseAiControlService;
import com.arte.app.platform.ai.DatabaseAiControlStartupSafety;
import com.arte.app.platform.ai.ReviewedModelConnectionTemplates;
import com.arte.app.platform.persistence.mapper.PlatformAiControlMapper;
import com.arte.app.web.controller.platform.WorkspaceAiConfigurationController;
import com.arte.core.api.workspace.WorkspacePolicyService;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.workspace.PolicyRules;
import com.arte.core.pojo.workspace.TenantCommands;
import com.arte.core.pojo.workspace.WorkspaceCommands;
import com.arte.core.spi.platform.AuditSink;
import com.arte.core.spi.workspace.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.URI;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 隔离数据库、正式 Mapper／治理事务／HTTP；不连接业务库，不访问供应商。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/01 13:50 ✾
 */
class DatabaseConnectionIntegrationTest {

    final DatabaseAiControlIntegrationTest f = new DatabaseAiControlIntegrationTest();
    Scope scope;

    @BeforeEach
    void setup() throws Exception {
        f.setup();
        scope = f.f.initialize().value().workspace().scope();
        f.f.jdbc.execute("CREATE TABLE arte_rbac_relation(source VARCHAR(256),binding_type VARCHAR(96),target VARCHAR(256))");
        f.f.jdbc.execute("CREATE TABLE arte_rbac_role(role_code VARCHAR(96),status VARCHAR(8))");
        f.f.jdbc.update("INSERT INTO arte_rbac_relation VALUES('alice','user_to_operation','aiConfiguration:manage')");
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    ApplicationContextRunner context() {
        return f.context().withUserConfiguration(DatabaseAiControlStartupSafety.class)
                .withPropertyValues("arte.ai-new-generation.allowed-origins[0]=https://api.deepseek.com");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void incompatibleCatalogFailsStartupWithRevisionAndFieldWithoutChangingData(boolean explicitNull) {
        Map<String, Object> payload = new LinkedHashMap<>();
        for (String field : List.of("capabilities", "bindings", "connections", "rates", "legacyGrants", "legacyBudgets", "disabledConnections")) {
            payload.put(field, List.of());
        }
        payload.put("defaults", new com.arte.app.platform.ai.AiControlData.Preset(false, List.of(), List.of()));
        if (explicitNull) {
            payload.put("reviewedPlans", null);
        }
        String encoded = f.f.json.write(payload);
        f.f.jdbc.update("INSERT INTO arte_ai_control_revision(version,payload,published_by,published_at) VALUES(1,?,?,CURRENT_TIMESTAMP)",
                encoded, f.f.actor.principal().subjectId());
        f.f.jdbc.update("UPDATE arte_ai_control_head SET version=1 WHERE id=1");
        context().run(c -> {
            Throwable failure = c.getStartupFailure();
            assertNotNull(failure);
            List<String> messages = new ArrayList<>();
            while (failure != null) {
                assertFalse(failure instanceof NullPointerException);
                messages.add(String.valueOf(failure.getMessage()));
                failure = failure.getCause();
            }
            assertTrue(messages.stream().anyMatch(message -> message.contains("reviewedPlans")), messages.toString());
            assertTrue(messages.stream().anyMatch(message -> message.contains("修订 1")), messages.toString());
        });
        assertEquals(encoded, f.f.jdbc.queryForObject("SELECT payload FROM arte_ai_control_revision WHERE version=1", String.class));
        assertEquals(1, f.f.jdbc.queryForObject("SELECT version FROM arte_ai_control_head WHERE id=1", Integer.class));
        assertEquals(0, f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_workspace_configuration", Integer.class));
        assertEquals(0, f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account", Integer.class));
    }

    @Test
    void currentEmptyCatalogWritesReviewedPlansAndStartsAfterReassembly() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            c.getBean(DatabaseAiControlService.class).initializeCatalog(f.f.actor, f.meta("current-empty-catalog"));
        });
        assertTrue(f.f.jdbc.queryForObject("SELECT payload FROM arte_ai_control_revision WHERE version=1", String.class)
                .contains("\"reviewedPlans\":[]"));
        context().run(c -> {
            assertNull(c.getStartupFailure());
            assertTrue(c.getBean(DatabaseAiControlService.class).catalog().catalog().reviewedPlans().isEmpty());
        });
    }

    @Test
    void emptyDatabaseStartsRealGenerationWithoutAnyFileModelDefinitions() {
        new ApplicationContextRunner().withUserConfiguration(
                        com.arte.app.platform.workspace.PlatformWorkspaceConfiguration.class,
                        com.arte.app.platform.identity.PlatformIdentityConfiguration.class, PlatformPersistenceConfiguration.class,
                        DatabaseAiControlService.class, DatabaseAiControlIntegrationTest.Stores.class,
                        com.arte.ainew.config.NewAiAdmissionConfiguration.class, DatabaseAiControlStartupSafety.class)
                .withConfiguration(org.springframework.boot.autoconfigure.AutoConfigurations.of(com.arte.ainew.config.NewAiGenerationConfiguration.class))
                .withBean("appDataSource", javax.sql.DataSource.class, () -> f.f.source)
                .withBean(com.arte.app.platform.identity.RbacAccountReader.class, () -> new com.arte.app.platform.identity.RbacAccountReader(
                        f.f.source, new com.arte.app.platform.identity.RbacAccountMapperConfiguration().identityUserMapper(f.f.source)))
                .withBean(org.redisson.api.RedissonClient.class, () -> org.mockito.Mockito.mock(org.redisson.api.RedissonClient.class))
                .withPropertyValues("arte.platform.identity.enabled=true", "arte.platform.identity.legacy-user-id-ceiling=0",
                        "arte.platform.persistence.enabled=true", "arte.platform.workspace.enabled=true",
                        "arte.ai-new.enabled=true", "arte.ai-new.configuration-mode=DATABASE", "arte.ai-new.release-ref=empty-database",
                        "arte.ai-new-generation.enabled=true", "arte.ai-new-generation.allowed-origins[0]=https://api.deepseek.com")
                .run(c -> {
                    assertNull(c.getStartupFailure());
                    assertNotNull(c.getBean(com.arte.ainew.spi.gateway.ModelGateway.class));
                    assertTrue(c.getBean(DatabaseAiControlService.class).definitions().connections().isEmpty());
                });
    }

    DatabaseAiControlService.ConnectionSettings settings(String path) {
        return new DatabaseAiControlService.ConnectionSettings("reviewed-deepseek", "deepseek",
                URI.create("https://api.deepseek.com/" + path), 5, 120, 1048576);
    }

    @Test
    void emptyDatabaseIgnoresDeploymentModelsAndAllowsExplicitEmptyInitialization() {
        context().run(c -> {
            assertNull(c.getStartupFailure());
            var db = c.getBean(DatabaseAiControlService.class);
            // 夹具故意提供完整文件种子；数据库未初始化时也不得回退它。
            assertTrue(db.definitions().connections().isEmpty());
            assertTrue(db.definitions().bindings().isEmpty());
            assertTrue(db.definitions().rates().isEmpty());
            assertTrue(db.definitions().grants().isEmpty());
            var initialized = db.initializeCatalog(f.f.actor, f.meta("initialize"));
            assertEquals(1, initialized.resultVersion());
            assertEquals(initialized.operationId(), db.initializeCatalog(f.f.actor, f.meta("initialize")).operationId());
            assertTrue(db.catalog().catalog().connections().isEmpty());
            assertFalse(db.catalog().catalog().defaults().enabled());
            assertNotNull(DatabaseAiTestCatalog.prepareWithAccounts(c, f.f.actor, scope).configuration());
        });
    }

    @Test
    void databaseModeRejectsTheLegacyFileDefinitionsProfile() {
        context().withInitializer(c -> c.getEnvironment().setActiveProfiles("ai-fixed"))
                .run(c -> assertNotNull(c.getStartupFailure()));
    }

    @Test
    void firstConnectionInitializesDatabaseAndSurvivesReassemblyWithoutSeedFallback() {
        context().run(c -> {
            var db = c.getBean(DatabaseAiControlService.class);
            var first = db.publishConnection(f.f.actor, f.meta("first"), 0, settings("v1"));
            assertEquals(1, first.value().version());
            assertEquals(first.operationId(), db.publishConnection(f.f.actor, f.meta("first"), 0, settings("v1")).operationId());
            assertThrows(PlatformException.class, () -> db.publishConnection(f.f.actor, f.meta("first"), 0, settings("different")));
            assertThrows(PlatformException.class, () -> db.publishConnection(f.f.actor, f.meta("stale"), 0, settings("v2")));
            var second = db.publishConnection(f.f.actor, f.meta("second"), 1, settings("v2"));
            assertEquals(2, second.value().catalog().connections().size());
            assertEquals(first.value().catalog().connections().getFirst(), second.value().catalog().connections().getFirst());
            assertEquals("db-v2", second.value().catalog().connections().getLast().definition().version());
            assertTrue(second.value().catalog().connections().stream().allMatch(value -> value.credential() == null));
        });
        context().run(c -> {
            assertNull(c.getStartupFailure());
            var db = c.getBean(DatabaseAiControlService.class);
            assertEquals(2, db.connectionCatalog(f.f.actor).version());
            assertEquals(2, db.definitions().connections().size());
            assertTrue(db.definitions().connections().stream().allMatch(value -> value.definition().id().equals("reviewed-deepseek")));
        });
    }

    @Test
    void templatesUseDatabaseConnectionsAndBothPoliciesAndCurrentDisableState() {
        context().run(c -> {
            var db = c.getBean(DatabaseAiControlService.class);
            var saved = db.publishConnection(f.f.actor, f.meta("first"), 0, settings("v1"));
            assertTrue(templates(c).isEmpty());
            allowPersonalConnection(c);
            assertEquals(saved.value().catalog().connections().getFirst().definition(), templates(c).getFirst().definition());
            db.setConnectionEnabled(f.f.actor, f.meta("disable"), 1, "reviewed-deepseek", false);
            assertTrue(templates(c).isEmpty());
            assertEquals(com.arte.ainew.pojo.control.ConnectionDefinition.State.DISABLED, db.definitions().connections().getFirst().state());
            // 当前停用不能覆盖历史发布内容。
            assertEquals(com.arte.ainew.pojo.control.ConnectionDefinition.State.ENABLED,
                    f.f.json.read(c.getBean(PlatformAiControlMapper.class).revision(1), com.arte.app.platform.ai.AiControlData.Catalog.class).connections().getFirst().state());
            db.setConnectionEnabled(f.f.actor, f.meta("enable"), 2, "reviewed-deepseek", true);
            assertEquals(1, templates(c).size());
        });
    }

    @Test
    void authorityFailureIsNotAnEmptyCatalogAndCurrentPermissionProtectsReplays() {
        context().run(c -> {
            var db = c.getBean(DatabaseAiControlService.class);
            db.publishConnection(f.f.actor, f.meta("first"), 0, settings("v1"));
            f.f.jdbc.update("DELETE FROM arte_rbac_relation");
            assertThrows(PlatformException.class, () -> db.connectionCatalog(f.f.actor));
            assertThrows(PlatformException.class, () -> db.publishConnection(f.f.actor, f.meta("first"), 0, settings("v1")));
            assertEquals(1, f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_control_revision", Integer.class));
            f.f.jdbc.update("DELETE FROM arte_ai_control_head");
            assertThrows(PlatformException.class, db::definitions);
        });
        context().run(c -> assertNotNull(c.getStartupFailure()));
    }

    @Test
    void originLimitsAndAuditFailureCannotPartiallyPublishConnection() {
        context().run(c -> {
            var db = c.getBean(DatabaseAiControlService.class);
            assertThrows(IllegalArgumentException.class, () -> db.publishConnection(f.f.actor, f.meta("origin"), 0,
                    new DatabaseAiControlService.ConnectionSettings("denied", "deepseek", URI.create("https://example.invalid/v1"), 5, 120, 1024)));
            Object effects = ReflectionTestUtils.getField(db, "effects");
            ReflectionTestUtils.setField(effects, "audit", (AuditSink) entry -> {
                throw new IllegalStateException("audit unavailable");
            });
            assertThrows(IllegalStateException.class, () -> db.publishConnection(f.f.actor, f.meta("rollback"), 0, settings("v1")));
            assertEquals(0, f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_control_revision", Integer.class));
            assertEquals(0L, f.f.jdbc.queryForObject("SELECT version FROM arte_ai_control_head WHERE id=1", Long.class));
        });
    }

    @Test
    void egressIsIndependentOfCatalogPermissionAndTakesEffectOnExistingPolicyInstances() {
        f.allowOrigins();
        context().run(c -> {
            var db = c.getBean(DatabaseAiControlService.class);
            var policy = c.getBean("newAiModelEgressPolicy", HttpEgressPolicy.class);
            var actor = f.f.actor;
            var endpoint = settings("v1").endpoint();
            assertFalse(db.configurationCapabilities(actor).manageEgress());
            assertThrows(PlatformException.class, () -> db.getEgressPolicy(actor));
            assertThrows(PlatformException.class, () -> db.updateEgressPolicy(actor, f.meta("unauthorized"), 0, List.of()));
            assertThrows(GenerationException.class, () -> policy.validateOrigin(endpoint));
            assertThrows(IllegalArgumentException.class, () -> db.publishConnection(actor, f.meta("empty-denied"), 0, settings("v1")));

            f.f.jdbc.update("INSERT INTO arte_rbac_relation VALUES('alice','user_to_operation','aiSecurity:manageEgress')");
            assertTrue(db.configurationCapabilities(actor).manageEgress());
            assertThrows(IllegalArgumentException.class, () -> db.updateEgressPolicy(actor, f.meta("bad-origin"), 0,
                    List.of("https://api.deepseek.com/v1")));
            var allowed = db.updateEgressPolicy(actor, f.meta("allow"), 0, List.of("HTTPS://API.DEEPSEEK.COM:443/"));
            assertEquals(List.of("https://api.deepseek.com"), allowed.value().origins());
            assertEquals(allowed.operationId(), db.updateEgressPolicy(actor, f.meta("allow"), 0,
                    List.of("https://api.deepseek.com")).operationId());
            policy.validateOrigin(endpoint);
            db.publishConnection(actor, f.meta("allowed-connection"), 0, settings("v1"));

            assertThrows(PlatformException.class, () -> db.updateEgressPolicy(actor, f.meta("stale"), 0, List.of()));
            assertEquals(2, db.updateEgressPolicy(actor, f.meta("revoke"), 1, List.of()).value().version());
            assertEquals("EGRESS_ORIGIN_DENIED", assertThrows(GenerationException.class,
                    () -> policy.validateOrigin(endpoint)).getMessage());
            assertEquals(1, db.catalog().catalog().connections().size());
        });
        context().run(c -> {
            var current = c.getBean(DatabaseAiControlService.class).getEgressPolicy(f.f.actor);
            assertEquals(2, current.version());
            assertTrue(current.origins().isEmpty());
        });
    }

    @Test
    void httpPublishesSafeDtoRejectsSecretsAndRequiresCurrentPlatformPermission() {
        context().withUserConfiguration(DatabaseAiControlIntegrationTest.HttpSecurity.class, WorkspaceAiConfigurationController.class)
                .withBean("pcs", com.arte.app.web.security.PermissionCheckService.class, com.arte.app.web.security.PermissionCheckService::new)
                .run(c -> {
                    var mapper = com.arte.core.serialize.SerializerFactory.buildJsonMapperWithoutTypeProperty();
                    var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(c.getBean(WorkspaceAiConfigurationController.class))
                            .setControllerAdvice(new com.arte.app.web.advice.PlatformExceptionHandler())
                            .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(mapper)).build();
                    var online = new com.arte.core.pojo.UserOnlineInfo().setId(1L).setUserName("alice").setAccountCreatedAt(f.f.jdbc.queryForObject(
                            "SELECT create_time FROM arte_rbac_user WHERE id=1", (row, index) -> row.getObject(1, java.time.LocalDateTime.class)));
                    var auth = org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(new com.arte.app.pojo.rbac.JwtUserDto(online), null, List.of());
                    try {
                        assertEquals(403, f.http(mvc, "getConnections", "{}", null, auth, online, false));
                        assertEquals(403, f.http(mvc, "getEgressPolicy", "{}", null, auth, online, true));
                        String request = mapper.writeValueAsString(java.util.Map.of("expectedVersion", 0, "settings", settings("v1")));
                        var saved = f.httpResponse(mvc, "publishConnection", request, "http-create", auth, online, true);
                        assertEquals(200, saved.getStatus(), saved.getContentAsString());
                        var body = mapper.readTree(saved.getContentAsString()).get("data");
                        assertEquals("db-v1", body.get("value").get("connections").get(0).get("reference").get("version").asString());
                        assertFalse(body.get("value").get("connections").get(0).has("credential"));
                        assertEquals(400, f.http(mvc, "publishConnection", request.replace("\"https://api.deepseek.com/v1\"", "null"), "no-endpoint", auth, online, true));
                        assertEquals(400, f.http(mvc, "publishConnection", request.replace("\"providerId\"", "\"token\":\"forbidden\",\"providerId\""), "secret", auth, online, true));
                        assertEquals(400, f.http(mvc, "publishConnection", request, null, auth, online, true));
                        f.f.jdbc.update("DELETE FROM arte_rbac_relation");
                        assertEquals(403, f.http(mvc, "publishConnection", request, "http-create", auth, online, true));
                        f.f.jdbc.update("INSERT INTO arte_rbac_relation VALUES('alice','user_to_operation','aiSecurity:manageEgress')");
                        assertEquals(200, f.http(mvc, "getEgressPolicy", "{}", null, auth, online, false));
                        assertEquals(200, f.http(mvc, "updateEgressPolicy", "{\"expectedVersion\":0,\"origins\":[]}", "http-egress", auth, online, false));
                        assertEquals(403, f.http(mvc, "getConnections", "{}", null, auth, online, true));
                    } catch (Exception failure) {
                        throw new AssertionError(failure);
                    }
                });
    }

    void allowPersonalConnection(ApplicationContext c) {
        var service = c.getBean(WorkspacePolicyService.class);
        var tenant = service.getTenantPolicyView(f.f.actor, scope.tenantId());
        var rules = tenant.policy().rules();
        var allowed = new PolicyRules(rules.allowedCreationTypes(), rules.extraCreationRoles(), rules.guestAiAllowed(), true,
                Set.of("chat-completions-sse"), Set.of("deepseek"), Set.of("api.deepseek.com"), rules.exportTypes(), rules.egressTypes(), rules.shareTargetKinds());
        service.updateTenantPolicy(f.f.actor, new TenantCommands.UpdateTenantPolicy(f.meta("tenant-policy"), scope.tenantId(),
                tenant.tenantVersion(), tenant.policy().version(), allowed, tenant.policy().membersMayCreateWorkspace()));
        var workspace = service.getWorkspacePolicyView(f.f.actor, scope);
        service.updateWorkspacePolicy(f.f.actor, new WorkspaceCommands.UpdateWorkspacePolicy(f.meta("space-policy"), scope,
                workspace.workspaceVersion(), workspace.policy().version(), allowed));
    }

    List<com.arte.ainew.pojo.control.ConnectionDefinition> templates(ApplicationContext c) {
        return f.f.tx.required(() -> {
            new WorkspaceGovernanceLocks(f.f.tx, c.getBean(TenantStore.class), c.getBean(WorkspaceStore.class),
                    c.getBean(TenantMembershipStore.class), c.getBean(WorkspaceMembershipStore.class), c.getBean(PreferenceStore.class))
                    .scopes(f.f.actor.principal().subjectId(), List.of(scope));
            return new ReviewedModelConnectionTemplates(c.getBean(DatabaseAiControlService.class)::definitions, c.getBean(PolicyStore.class),
                    c.getBean("newAiModelEgressPolicy", HttpEgressPolicy.class)).available(scope);
        });
    }
}
