package com.arte.app.platform.persistence;

import com.arte.ainew.api.model.ModelAclMigrationService;
import com.arte.ainew.api.model.ModelPublicationService;
import com.arte.ainew.api.model.ModelResourceService;
import com.arte.ainew.pojo.model.ModelResourceCommands;
import com.arte.ainew.pojo.model.ModelResourceVersion;
import com.arte.app.platform.ai.WorkspaceAiGovernanceService;
import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.web.controller.platform.ModelResourceController;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.pojo.access.AccessDecision;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.platform.Scope;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.core.Authentication;
import reactor.core.scheduler.Scheduler;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.arte.core.constant.platform.PlatformActions.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * M5 隔离新装、菜单和当前创建资格验收，不连接业务库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/03 22:20 ✾
 */
class ModelConfigurationM5IntegrationTest {

    @Test
    void creationOptionsAreReadOnlyAndRecheckTenantPermissionBeforeSubmitting() throws Exception {
        TenantModelSharingIntegrationTest fixture = new TenantModelSharingIntegrationTest();
        fixture.setup();
        try {
            fixture.context().run(context -> {
                ModelResourceService models = context.getBean(ModelResourceService.class);
                Scope scope = fixture.f.f.scope;
                int resources = fixture.f.f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_model_resource", Integer.class);
                int receipts = fixture.f.f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_command", Integer.class);
                assertEquals(List.of(ModelResourceCommands.ModelKind.PERSONAL, ModelResourceCommands.ModelKind.TENANT), models.creationOptions(fixture.actor(), scope));
                fixture.f.f.f.f.jdbc.update("DELETE FROM arte_rbac_relation WHERE target='aiConfiguration:manage'");
                assertEquals(List.of(ModelResourceCommands.ModelKind.PERSONAL), models.creationOptions(fixture.actor(), scope));
                assertThrows(RuntimeException.class, () -> models.create(fixture.actor(), new ModelResourceCommands.Create(fixture.meta("forged-tenant"), scope,
                        "Denied", ModelResourceCommands.ModelKind.TENANT)));
                assertEquals(resources, fixture.f.f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_model_resource", Integer.class));
                assertEquals(receipts, fixture.f.f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_platform_command", Integer.class));
                PlatformAuthenticatedExecutor executor = new PlatformAuthenticatedExecutor(context.getBean(RbacIdentityProvider.class),
                        new PlatformPersistenceExecutor(context.getBean("newAiPersistenceScheduler", Scheduler.class), fixture.f.f.f.f.tx));
                try (ManagementHttpHarness http = new ManagementHttpHarness(new ModelResourceController(executor, models,
                        context.getBean(ModelAclMigrationService.class)))) {
                    Authentication auth = ManagementHttpHarness.login(fixture.f.f.f.f.jdbc, 1, "alice");
                    String body = "{\"scope\":" + fixture.f.f.f.f.json.write(scope) + "}";
                    assertEquals("PERSONAL", http.ok("/ai-new/model-resource/getModelCreationOptions", body, null, auth).getJSONArray("data").getStr(0));
                    http.rejected("/ai-new/model-resource/getModelCreationOptions", body.substring(0, body.length() - 1) + ",\"extra\":true}", null, auth, 400, "INVALID_ARGUMENT");
                }
            });
        } finally {
            fixture.cleanup();
        }
    }

    @Test
    void publishedTenantModelBatchChecksReadSharingFactsInsideTransaction() throws Exception {
        TenantModelSharingIntegrationTest fixture = new TenantModelSharingIntegrationTest();
        fixture.setup();
        try {
            fixture.context().run(context -> {
                fixture.prepare(context);
                AuthorizationService authorization = context.getBean("platformAuthorizationService", AuthorizationService.class);
                List<AccessRequest> requests = List.of(MODEL_READ,
                                MODEL_EDIT, MODEL_USE).stream()
                        .map(action -> new AccessRequest(fixture.f.f.scope, fixture.f.model.resource(), action, "m5-batch")).toList();
                assertTrue(authorization.batchCheck(fixture.actor(), requests).stream().allMatch(AccessDecision::allowed));
                assertTrue(fixture.f.f.f.f.tx.readOnly(() -> authorization.batchCheck(fixture.actor(), requests)
                        .stream().allMatch(AccessDecision::allowed)));
                fixture.scopes(context, 0, Set.of());
                List<AccessDecision> denied = authorization.batchCheck(fixture.actor(), requests);
                assertTrue(denied.get(0).allowed());
                assertTrue(denied.get(1).allowed());
                assertFalse(denied.get(2).allowed());
                assertEquals(AccessReasonEnum.DOMAIN_DENIED, denied.get(2).reason());
            });
        } finally {
            fixture.cleanup();
        }
    }

    @Test
    void existingPersonalPublisherDoesNotNeedWorkspaceGovernanceOrPlatformMenu() throws Exception {
        PersonalModelPublicationIntegrationTest fixture = new PersonalModelPublicationIntegrationTest();
        fixture.setup();
        try {
            fixture.context().run(context -> {
                fixture.prepare(context);
                fixture.f.f.f.jdbc.update("UPDATE arte_platform_workspace_member SET role=2,version=version+1 WHERE workspace_id=? AND subject_id=?",
                        fixture.f.scope.workspaceId(), fixture.f.f.f.actor.principal().subjectId());
                assertFalse(context.getBean(WorkspaceAiGovernanceService.class).get(fixture.f.f.f.actor, fixture.f.scope).canManage());
                ModelResourceVersion version = context.getBean(ModelPublicationService.class).publish(fixture.f.f.f.actor,
                        fixture.f.f.meta("m5-member-publish"), fixture.f.scope, fixture.model.resource(), 0, 1, 1, fixture.validation,
                        fixture.f.f.ref("model-plan", "text"), 1).value();
                assertEquals(fixture.model.resource(), version.resource());
                assertEquals(0, fixture.f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account", Integer.class));
            });
        } finally {
            fixture.cleanup();
        }
    }

    @Test
    void newSchemaAndMenuInstallPreserveBusinessGrantsAndExposeMissingOldRoleMenu() throws Exception {
        TenantModelSharingIntegrationTest fixture = new TenantModelSharingIntegrationTest();
        fixture.setup();
        try {
            JdbcTemplate jdbc = fixture.f.f.f.f.jdbc;
            jdbc.execute("CREATE TABLE arte_rbac_menu(id BIGINT AUTO_INCREMENT PRIMARY KEY,menu_code VARCHAR(96),menu_name VARCHAR(100),icon VARCHAR(100),menu_url VARCHAR(256),father_id BIGINT,order_id INT,status INT,description VARCHAR(512),show_flag VARCHAR(8),row_version BIGINT,create_by VARCHAR(96),create_time DATETIME,update_by VARCHAR(96),update_time DATETIME)");
            jdbc.execute("ALTER TABLE arte_rbac_relation ADD create_by VARCHAR(96)");
            jdbc.execute("ALTER TABLE arte_rbac_relation ADD create_time DATETIME");
            jdbc.update("INSERT INTO arte_rbac_role VALUES('admin','1'),('governor','1')");
            jdbc.update("INSERT INTO arte_rbac_relation(source,binding_type,target) VALUES('governor','role_to_menu','aiModels')");
            int operations = jdbc.queryForObject("SELECT COUNT(*) FROM arte_rbac_relation WHERE binding_type LIKE '%operation'", Integer.class);
            String sql = Files.readString(Path.of("scripts/arte-environment-menu-dml-mysql.sql")).replaceAll("(?im)^SET\\s+[^;]*;", "");
            try (Connection connection = fixture.f.f.f.f.source.getConnection()) {
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
                ScriptUtils.executeSqlScript(connection, new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8)));
            }
            assertEquals(5, jdbc.queryForObject("SELECT COUNT(*) FROM arte_rbac_menu WHERE menu_code LIKE 'Environment%'", Integer.class));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_rbac_relation WHERE source='admin' AND target='EnvironmentWorkspaces'", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_rbac_relation WHERE source='governor' AND target='EnvironmentWorkspaces'", Integer.class));
            assertEquals(operations, jdbc.queryForObject("SELECT COUNT(*) FROM arte_rbac_relation WHERE binding_type LIKE '%operation'", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_workspace_budget_rule", Integer.class));
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_model_resource", Integer.class));
            String databaseColumn = fixture.f.f.f.f.mysql == null ? "TABLE_CATALOG" : "TABLE_SCHEMA";
            String database = fixture.f.f.f.f.database.toLowerCase(Locale.ROOT);
            assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME)='arte_ai_personal_publication' AND LOWER(" + databaseColumn + ")=?", Integer.class, database));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE LOWER(TABLE_NAME)='arte_ai_model_publication' AND LOWER(" + databaseColumn + ")=?", Integer.class, database));
        } finally {
            fixture.cleanup();
        }
    }
}
