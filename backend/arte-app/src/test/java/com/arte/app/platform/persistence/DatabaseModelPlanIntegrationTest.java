package com.arte.app.platform.persistence;

import com.arte.app.platform.ai.AiControlData.CatalogView;
import com.arte.app.platform.ai.DatabaseAiControlService;
import com.arte.app.platform.ai.ReviewedModelSettings;
import com.arte.app.web.controller.platform.WorkspaceAiConfigurationController;
import com.arte.app.web.platform.AiModelPlanPayload;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.UserOnlineInfo;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.spi.platform.AuditSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 独立模型方案使用真实隔离数据库、治理事务和 MVC，不依赖文件模型定义。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/01 14:45 ✾
 */
class DatabaseModelPlanIntegrationTest {

    final DatabaseConnectionIntegrationTest f = new DatabaseConnectionIntegrationTest();

    @BeforeEach
    void setup() throws Exception {
        f.setup();
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    ReviewedModelSettings settings(String price) {
        return new ReviewedModelSettings("brand-new", new DefinitionRef("connection", "reviewed-deepseek", "db-v1"),
                "never-seeded-model", 65536, "CNY", price, "2.123456789012345678", true);
    }

    DatabaseAiControlService prepare(org.springframework.context.ApplicationContext context) {
        DatabaseAiControlService service = context.getBean(DatabaseAiControlService.class);
        assertTrue(service.definitions().bindings().isEmpty());
        service.publishConnection(f.f.f.actor, f.f.meta("connection"), 0, f.settings("v1"));
        return service;
    }

    @Test
    void independentPlanPersistsExactPricesWithoutGrantBudgetOrFileSeedAndSurvivesRestart() {
        f.context().run(c -> {
            DatabaseAiControlService service = prepare(c);
            CatalogView view = service.publishModelPlan(f.f.f.actor, f.f.meta("plan"), 1, settings("0.000000000000000001")).value();
            assertEquals(2, view.version());
            assertEquals("never-seeded-model", view.catalog().bindings().getFirst().remoteOperation());
            assertEquals("reviewed-brand-new", view.catalog().bindings().getFirst().definition().id());
            assertEquals("0.000000000000000001", AiModelPlanPayload.View.from(view).plans().getFirst().inputPerMillion());
            assertTrue(view.catalog().legacyGrants().isEmpty());
            assertTrue(view.catalog().legacyBudgets().isEmpty());
            assertFalse(view.catalog().defaults().enabled());
            assertTrue(view.catalog().defaults().bindings().isEmpty());
        });
        f.context().run(c -> {
            DatabaseAiControlService service = c.getBean(DatabaseAiControlService.class);
            assertEquals(2, service.catalog().version());
            assertEquals("2.123456789012345678", service.definitions().rates().getFirst().outputPerMillion().amount().toPlainString());
            assertEquals(1, service.definitions().bindings().size());
        });
    }

    @Test
    void repricingAppendsImmutableDependenciesAndSameKeyReplaysWithoutCompetingVersions() {
        f.context().run(c -> {
            DatabaseAiControlService service = prepare(c);
            com.arte.core.pojo.platform.CommandReceipt<CatalogView> first = service.publishModelPlan(f.f.f.actor, f.f.meta("first"), 1, settings("1.00"));
            assertEquals(first.operationId(), service.publishModelPlan(f.f.f.actor, f.f.meta("first"), 1, settings("01.0")).operationId());
            assertThrows(PlatformException.class, () -> service.publishModelPlan(f.f.f.actor, f.f.meta("first"), 1, settings("2")));
            assertThrows(PlatformException.class, () -> service.publishModelPlan(f.f.f.actor, f.f.meta("stale"), 1, settings("2")));
            CatalogView next = service.publishModelPlan(f.f.f.actor, f.f.meta("second"), 2, settings("3")).value();
            assertEquals(2, next.catalog().rates().size());
            assertEquals(first.value().catalog().rates().getFirst(), next.catalog().rates().getFirst());
            assertEquals(first.value().catalog().bindings().getFirst(), next.catalog().bindings().getFirst());
            assertEquals("db-v3", next.catalog().bindings().getLast().definition().version());
            assertEquals("1", service.historicalRate(first.value().catalog().rates().getFirst().definition()).inputPerMillion().amount().toPlainString());
            assertEquals("3", service.historicalRate(next.catalog().rates().getLast().definition()).inputPerMillion().amount().toPlainString());
            assertEquals(first.operationId(), service.publishModelPlan(f.f.f.actor, f.f.meta("first"), 1, settings("1")).operationId());
            assertEquals(3, service.catalog().version());
        });
    }

    @Test
    void rejectsInvalidPricesAndUnresolvedOrDisabledConnectionsWithoutPartialDefinitions() {
        assertThrows(IllegalArgumentException.class, () -> settings("-1"));
        assertThrows(IllegalArgumentException.class, () -> settings("1e-8"));
        assertThrows(IllegalArgumentException.class, () -> settings("0.0000000000000000001"));
        f.context().run(c -> {
            DatabaseAiControlService service = c.getBean(DatabaseAiControlService.class);
            assertThrows(IllegalArgumentException.class, () -> service.publishModelPlan(f.f.f.actor, f.f.meta("missing"), 0, settings("1")));
            service.publishConnection(f.f.f.actor, f.f.meta("connection"), 0, f.settings("v1"));
            service.setConnectionEnabled(f.f.f.actor, f.f.meta("disable"), 1, "reviewed-deepseek", false);
            assertThrows(IllegalArgumentException.class, () -> service.publishModelPlan(f.f.f.actor, f.f.meta("disabled"), 2, settings("1")));
            assertEquals(2, service.catalog().version());
            assertTrue(service.definitions().rates().isEmpty());
        });
    }

    @Test
    void currentPermissionAndAuditFailureProtectBothNewCommandsAndReplays() {
        f.context().run(c -> {
            DatabaseAiControlService service = prepare(c);
            service.publishModelPlan(f.f.f.actor, f.f.meta("first"), 1, settings("1"));
            Object effects = ReflectionTestUtils.getField(service, "effects");
            ReflectionTestUtils.setField(effects, "audit", (AuditSink) entry -> {
                throw new IllegalStateException("audit unavailable");
            });
            assertThrows(IllegalStateException.class, () -> service.publishModelPlan(f.f.f.actor, f.f.meta("rollback"), 2, settings("3")));
            assertEquals(2, service.catalog().version());
            assertEquals(1, service.definitions().rates().size());
            f.f.f.jdbc.update("DELETE FROM arte_rbac_relation");
            assertThrows(PlatformException.class, () -> service.publishModelPlan(f.f.f.actor, f.f.meta("first"), 1, settings("1")));
            assertThrows(PlatformException.class, () -> service.publishModelPlan(f.f.f.actor, f.f.meta("denied"), 2, settings("1")));
        });
    }

    @Test
    void mvcRequiresPermissionStrictFieldsAndOperationKeyAndReturnsOnlySafePlans() {
        f.context().withUserConfiguration(DatabaseAiControlIntegrationTest.HttpSecurity.class, WorkspaceAiConfigurationController.class)
                .withBean("pcs", com.arte.app.web.security.PermissionCheckService.class, com.arte.app.web.security.PermissionCheckService::new).run(c -> {
                    prepare(c);
                    tools.jackson.databind.json.JsonMapper mapper = com.arte.core.serialize.SerializerFactory.buildJsonMapperWithoutTypeProperty();
                    org.springframework.test.web.servlet.MockMvc mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders
                            .standaloneSetup(c.getBean(WorkspaceAiConfigurationController.class)).setControllerAdvice(new com.arte.app.web.advice.PlatformExceptionHandler())
                            .setMessageConverters(new org.springframework.http.converter.json.JacksonJsonHttpMessageConverter(mapper)).build();
                    UserOnlineInfo online = new UserOnlineInfo().setId(1L).setUserName("alice").setAccountCreatedAt(f.f.f.jdbc.queryForObject(
                            "SELECT create_time FROM arte_rbac_user WHERE id=1", (row, index) -> row.getObject(1, java.time.LocalDateTime.class)));
                    org.springframework.security.core.Authentication auth = org.springframework.security.authentication.UsernamePasswordAuthenticationToken
                            .authenticated(new com.arte.app.pojo.rbac.JwtUserDto(online), null, List.of());
                    try {
                        assertEquals(403, f.f.http(mvc, "getModelPlans", "{}", null, auth, online, false));
                        String request = mapper.writeValueAsString(Map.of("expectedVersion", 1, "settings", settings("1")));
                        assertEquals(400, f.f.http(mvc, "publishModelPlan", request, null, auth, online, true));
                        assertEquals(400, f.f.http(mvc, "publishModelPlan", request.replace("\"currency\"", "\"token\":\"secret\",\"currency\""), "secret", auth, online, true));
                        org.springframework.mock.web.MockHttpServletResponse result = f.f.httpResponse(mvc, "publishModelPlan", request, "http-plan", auth, online, true);
                        assertEquals(200, result.getStatus(), result.getContentAsString());
                        String body = result.getContentAsString();
                        assertTrue(body.contains("never-seeded-model"));
                        assertFalse(body.contains("legacyGrants"));
                        assertFalse(body.contains("endpoint"));
                        assertFalse(body.contains("credential"));
                        assertEquals(200, f.f.http(mvc, "getModelPlans", "{}", null, auth, online, true));
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                });
    }
}
