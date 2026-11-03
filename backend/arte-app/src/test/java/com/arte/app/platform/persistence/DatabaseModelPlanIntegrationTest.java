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
            assertEquals("never-seeded-model", view.catalog().reviewedPlans().getFirst().remoteModel());
            assertEquals("reviewed-brand-new", view.catalog().reviewedPlans().getFirst().planRef().id());
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
            assertTrue(service.definitions().bindings().isEmpty());
            assertEquals(1, service.catalog().catalog().reviewedPlans().size());
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
            assertEquals(first.value().catalog().reviewedPlans().getFirst(), next.catalog().reviewedPlans().getFirst());
            assertEquals("v2", next.catalog().reviewedPlans().getLast().planRef().version());
            assertTrue(next.catalog().bindings().isEmpty());
            assertTrue(next.catalog().capabilities().isEmpty());
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
                        assertEquals(404, f.f.http(mvc, "publishCatalog", "{}", "old-path", auth, online, true));
                        String policy = "{\"expectedCatalogVersion\":2,\"enabled\":true,\"rateCaps\":[]}";
                        assertEquals(403, f.f.http(mvc, "updateDefaultAiPolicy", policy, "policy", auth, online, false));
                        assertEquals(400, f.f.http(mvc, "updateDefaultAiPolicy", policy, null, auth, online, true));
                        assertEquals(400, f.f.http(mvc, "updateDefaultAiPolicy", policy.replace("\"enabled\"", "\"bindings\":[],\"enabled\""), "inject", auth, online, true));
                        assertEquals(200, f.f.http(mvc, "updateDefaultAiPolicy", policy, "policy", auth, online, true));
                        String caps = mapper.writeValueAsString(Map.of("expectedCatalogVersion", 3, "enabled", true, "rateCaps", List.of(
                                Map.of("rate", new DefinitionRef("rate", "reviewed-brand-new", "v1"),
                                        "limit", Map.of("amount", "0.000000000000000001", "currency", "CNY")))));
                        org.springframework.mock.web.MockHttpServletResponse capResult = f.f.httpResponse(mvc, "updateDefaultAiPolicy", caps, "exact-cap", auth, online, true);
                        assertEquals(200, capResult.getStatus(), capResult.getContentAsString());
                        assertEquals("0.000000000000000001", mapper.readTree(capResult.getContentAsString()).get("data").get("value")
                                .get("catalog").get("defaults").get("rateCaps").get(0).get("limit").get("amount").asString());
                        assertEquals(400, f.f.http(mvc, "updateDefaultAiPolicy", caps.replace("\"currency\"", "\"secret\":\"hidden\",\"currency\""), "nested-inject", auth, online, true));
                    } catch (Exception error) {
                        throw new AssertionError(error);
                    }
                });
    }

    @Test
    void capacityAndConnectionRevisionsDoNotChangeTheRateOrFollowGlobalRevisionNumbers() {
        f.context().run(c -> {
            DatabaseAiControlService service = prepare(c);
            CatalogView first = service.publishModelPlan(f.f.f.actor, f.f.meta("first-plan"), 1, settings("1")).value();
            service.publishConnection(f.f.f.actor, f.f.meta("unrelated-connection"), 2, f.settings("v2"));
            ReviewedModelSettings changed = new ReviewedModelSettings("brand-new", settings("1").connection(),
                    "never-seeded-model", 32768, "CNY", "01.00", "2.123456789012345678", false);
            CatalogView second = service.publishModelPlan(f.f.f.actor, f.f.meta("capacity-only"), 3, changed).value();
            assertEquals("v2", second.catalog().reviewedPlans().getLast().planRef().version());
            assertEquals(4, second.catalog().reviewedPlans().getLast().catalogRevision());
            assertEquals(first.catalog().reviewedPlans().getFirst().rate(), second.catalog().reviewedPlans().getLast().rate());
            assertEquals(1, second.catalog().rates().size());
            assertEquals(2, second.catalog().reviewedPlans().size());
            assertTrue(service.definitions().bindings().isEmpty());
            assertTrue(service.definitions().capabilities().isEmpty());
        });
    }

    @Test
    void changingRemoteModelOrCurrencyCreatesAnExplicitNewRate() {
        f.context().run(c -> {
            DatabaseAiControlService service = prepare(c);
            CatalogView first = service.publishModelPlan(f.f.f.actor, f.f.meta("first"), 1, settings("1")).value();
            ReviewedModelSettings target = new ReviewedModelSettings("brand-new", settings("1").connection(),
                    "another-model", 65536, "CNY", "1", "2.123456789012345678", true);
            CatalogView second = service.publishModelPlan(f.f.f.actor, f.f.meta("target"), 2, target).value();
            assertNotEquals(first.catalog().reviewedPlans().getFirst().rate(), second.catalog().reviewedPlans().getLast().rate());
            ReviewedModelSettings currency = new ReviewedModelSettings("brand-new", settings("1").connection(),
                    "another-model", 65536, "USD", "1", "2.123456789012345678", true);
            CatalogView third = service.publishModelPlan(f.f.f.actor, f.f.meta("currency"), 3, currency).value();
            assertEquals("v3", third.catalog().reviewedPlans().getLast().rate().version());
            assertEquals(3, third.catalog().rates().size());
        });
    }

    @Test
    void explicitToolDeclarationIsRetainedAndUnsupportedAdapterCannotApproveIt() {
        f.context().run(c -> {
            DatabaseAiControlService service = prepare(c);
            ReviewedModelSettings tools = new ReviewedModelSettings("brand-new", settings("1").connection(),
                    "never-seeded-model", 65536, "CNY", "1", "2", true, true);
            CatalogView approved = service.publishModelPlan(f.f.f.actor, f.f.meta("tools"), 1, tools).value();
            assertTrue(approved.catalog().reviewedPlans().getFirst().features().contains(com.arte.ainew.pojo.control.CapabilityDescriptor.Feature.TOOL_CALLS));
            assertTrue(AiModelPlanPayload.View.from(approved).plans().getFirst().toolCalls());
            com.arte.ainew.spi.gateway.ModelGateway unsupported = org.mockito.Mockito.mock(com.arte.ainew.spi.gateway.ModelGateway.class);
            org.mockito.Mockito.when(unsupported.supportsTextChat(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any())).thenReturn(true);
            org.springframework.beans.factory.support.StaticListableBeanFactory factory = new org.springframework.beans.factory.support.StaticListableBeanFactory(Map.of("gateway", unsupported));
            ReflectionTestUtils.setField(service, "modelGateways", factory.getBeanProvider(com.arte.ainew.spi.gateway.ModelGateway.class));
            assertThrows(IllegalArgumentException.class, () -> service.publishModelPlan(f.f.f.actor, f.f.meta("unsupported-tools"), 2, tools));
            assertEquals(2, service.catalog().version());
            assertEquals(1, service.catalog().catalog().reviewedPlans().size());
        });
    }

    @Test
    void defaultPolicyIsIndependentAndPlansRemainUndiscoverableEvenWithAnApprovedBudget() {
        f.context().run(c -> {
            DatabaseAiControlService service = prepare(c);
            CatalogView plan = service.publishModelPlan(f.f.f.actor, f.f.meta("plan"), 1, settings("1")).value();
            com.arte.app.platform.ai.AiControlData.BudgetPreset cap = new com.arte.app.platform.ai.AiControlData.BudgetPreset(
                    plan.catalog().reviewedPlans().getFirst().rate(), new com.arte.ainew.pojo.budget.Money(new java.math.BigDecimal("100"), java.util.Currency.getInstance("CNY")));
            long accounts = f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account", Long.class);
            long spaces = f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_workspace_configuration", Long.class);
            com.arte.core.pojo.platform.CommandReceipt<CatalogView> updated = service.updateDefaultAiPolicy(f.f.f.actor, f.f.meta("policy"), 2, true, List.of(cap));
            assertEquals(updated.operationId(), service.updateDefaultAiPolicy(f.f.f.actor, f.f.meta("policy"), 2, true, List.of(cap)).operationId());
            assertEquals(accounts, f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_account", Long.class));
            assertEquals(spaces, f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_workspace_configuration", Long.class));
            assertTrue(updated.value().catalog().defaults().bindings().isEmpty());
            assertThrows(IllegalArgumentException.class, () -> service.updateDefaultAiPolicy(f.f.f.actor, f.f.meta("duplicate"), 3, true, List.of(cap, cap)));
            DatabaseAiTestCatalog.prepare(c, f.f.f.actor, f.scope);
            com.arte.ainew.common.execution.ExecutionContext execution = f.f.execution(service, f.scope,
                    java.util.Set.of(com.arte.ainew.application.auth.AdmissionAuthorization.INVOKE), "review-is-not-execution");
            assertTrue(c.getBean(com.arte.ainew.application.control.ChatConfigurationQueryService.class).discover(execution).block(java.time.Duration.ofSeconds(10)).options().isEmpty());
            assertThrows(RuntimeException.class, () -> c.getBean(com.arte.ainew.api.control.BindingManager.class).resolve(
                    plan.catalog().reviewedPlans().getFirst().planRef(), new DefinitionRef("capability", "reviewed-brand-new", "v1"), execution).block(java.time.Duration.ofSeconds(10)));
            service.updateDefaultAiPolicy(f.f.f.actor, f.f.meta("changed-default"), 3, false, List.of());
            assertTrue(service.get(f.f.f.actor, f.scope).configuration().enabled());
        });
    }

}
