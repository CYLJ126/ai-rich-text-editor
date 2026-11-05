package com.arte.app.platform.persistence;

import com.arte.ainew.api.model.ModelDraftService;
import com.arte.ainew.api.model.ModelPublicationService;
import com.arte.ainew.api.model.ModelResourceService;
import com.arte.ainew.api.model.ModelUsagePreparationService;
import com.arte.ainew.application.control.ChatConfigurationQueryService;
import com.arte.ainew.pojo.model.*;
import com.arte.ainew.spi.auth.AiAdmissionAuthorizationGuard;
import com.arte.app.platform.ai.AiControlData;
import com.arte.app.platform.ai.DatabaseAiControlService;
import com.arte.app.platform.ai.WorkspaceAiGovernanceService;
import com.arte.app.platform.identity.RbacIdentityProvider;
import com.arte.app.web.controller.platform.WorkspaceAiConfigurationController;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.spi.platform.AuditSink;
import com.arte.core.spi.platform.PublicOutboxStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * M3 真实持久化、治理命令、本人准备与当前执行门控回归；不接触业务库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/03 15:30 ✾
 */
class WorkspaceAiGovernanceIntegrationTest {

    final PersonalModelPublicationIntegrationTest f = new PersonalModelPublicationIntegrationTest();

    @BeforeEach
    void setup() throws Exception {
        f.setup();
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    TrustedActor actor() {
        return f.f.f.f.actor;
    }

    Scope scope() {
        return f.f.scope;
    }

    CommandMeta meta(String key) {
        return new CommandMeta(key, "m3-test");
    }

    WorkspaceAiGovernanceService governance(ApplicationContext c) {
        return c.getBean(WorkspaceAiGovernanceService.class);
    }

    int count(String table) {
        return f.f.f.f.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    ModelResourceVersion publishOnly(ApplicationContext c, String key, long mv, long dv) {
        return c.getBean(ModelPublicationService.class).publish(actor(), meta(key), scope(), f.model.resource(), mv, dv, 1,
                f.validation, f.f.f.ref("model-plan", "text"), 1).value();
    }

    void rules(ApplicationContext c, List<AiControlData.BudgetPreset> rules) {
        AiControlData.BudgetRulesView old = governance(c).getRules(actor(), scope());
        governance(c).updateRules(actor(), meta(UUID.randomUUID().toString()), scope(), old.workspaceVersion(), old.configuration().version(), rules);
    }

    ModelUsagePreparation status(ApplicationContext c, ModelResourceVersion version) {
        return c.getBean(ModelUsagePreparationService.class).status(actor(), scope(), version.resource(), version.publication().binding().definition());
    }

    @Test
    void queriesNeverPrepareAndConcurrentFirstPreparationCommitsOneEmptySelectionAndNoAccounts() {
        f.context().run(c -> {
            DatabaseAiControlService control = c.getBean(DatabaseAiControlService.class);
            DatabaseAiTestCatalog.publish(control, c, actor(), meta("catalog"));
            WorkspaceAiGovernanceService service = governance(c);
            AiControlData.WorkspaceView before = service.get(actor(), scope());
            int receipts = count("arte_platform_command"), events = count("arte_platform_outbox");
            assertNull(before.configuration());
            assertNull(service.getRules(actor(), scope()).configuration());
            assertTrue(service.modelOptions(actor(), scope(), null).items().isEmpty());
            service.rateOptions(actor(), scope(), null);
            assertEquals(receipts, count("arte_platform_command"));
            assertEquals(events, count("arte_platform_outbox"));
            assertEquals(0, count("arte_ai_workspace_configuration"));
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
                List<Future<CommandReceipt<AiControlData.WorkspaceView>>> tasks = new ArrayList<>();
                for (int i = 0; i < 3; i++) {
                    tasks.add(pool.submit(() -> {
                        start.await();
                        return service.prepare(actor(), meta("same-first-key"), scope(), before.workspaceVersion(), 1);
                    }));
                }
                start.countDown();
                String id = tasks.getFirst().get(20, TimeUnit.SECONDS).operationId();
                for (Future<CommandReceipt<AiControlData.WorkspaceView>> task : tasks) {
                    assertEquals(id, task.get(20, TimeUnit.SECONDS).operationId());
                }
            } catch (Exception failure) {
                throw new AssertionError(failure);
            }
            assertTrue(service.get(actor(), scope()).configuration().models().isEmpty());
            assertEquals(1, count("arte_ai_workspace_budget_rule"));
            assertEquals(0, count("arte_ai_account"));
            assertEquals(0, count("arte_ai_budget_definition"));
            assertThrows(PlatformException.class, () -> service.prepare(actor(), meta("another-first-key"), scope(), before.workspaceVersion(), 1));
        });
    }

    @Test
    void readableCandidatesExistBeforeBudgetAndSelectionDoesNotChangeRulesOrPrepareAccount() {
        f.context().run(c -> {
            f.prepare(c);
            ModelResourceVersion version = publishOnly(c, "publish", 0, 1);
            assertEquals("MODEL_NOT_ENABLED", status(c, version).reason());
            assertEquals(1, governance(c).modelOptions(actor(), scope(), null).items().size());
            assertEquals(0, count("arte_ai_account"));
            rules(c, List.of());
            AiControlData.BudgetRules before = governance(c).getRules(actor(), scope()).configuration();
            DatabaseAiTestCatalog.select(c, actor(), scope(), version.resource().resourceId(), version.publication().binding().definition());
            assertEquals(before, governance(c).getRules(actor(), scope()).configuration());
            assertEquals("BUDGET_RULE_NOT_CONFIGURED", status(c, version).reason());
            assertTrue(c.getBean(ChatConfigurationQueryService.class).discover(f.f.f.execution(c.getBean(DatabaseAiControlService.class), scope(), Set.of(com.arte.ainew.application.auth.AdmissionAuthorization.INVOKE, com.arte.ainew.application.auth.AdmissionAuthorization.READ), "no-budget")).block(f.WAIT).options().isEmpty());
            assertEquals(0, count("arte_ai_account"));
            AiControlData.BudgetPreset cap = governance(c).getRules(actor(), scope()).approvedCaps().getFirst();
            rules(c, List.of(cap));
            assertEquals(ModelUsagePreparation.State.PREPARABLE, status(c, version).state());
            // 模型所有者降为普通空间成员，仍可仅为本人准备，不需要治理页面或提额权限。
            f.f.f.f.jdbc.update("UPDATE arte_platform_workspace_member SET role=2,version=version+1 WHERE workspace_id=? AND subject_id=?", scope().workspaceId(), actor().principal().subjectId());
            assertEquals(List.of("PREPARE_OWN_BUDGET"), status(c, version).allowedActions());
            DatabaseAiTestCatalog.prepareOwn(c, actor(), scope(), version.resource().resourceId(), version.publication().binding().definition());
            assertEquals(ModelUsagePreparation.State.READY, status(c, version).state());
            assertThrows(PlatformException.class, () -> governance(c).getRules(actor(), scope()));
            assertEquals(1, count("arte_ai_account"));
        });
    }

    @Test
    void twoResourcesAtOneExactRateReuseAccountAndDeselectingOneKeepsOtherUsable() {
        f.context().run(c -> {
            f.prepare(c);
            ModelResourceVersion first = f.publishSelectedFixture(c, "first", 0, 1, 1);
            ModelResource firstModel = f.model;
            f.model = c.getBean(ModelResourceService.class).create(actor(), new ModelResourceCommands.Create(meta("second-model"), scope(), "Second")).value();
            f.save(c, 0, "deepseek-chat");
            c.getBean(ModelDraftService.class).replaceCredential(actor(), meta("second-key"), scope(), f.model.resource(), 0, f.TOKEN);
            f.validate(c, 1, 1, false);
            ModelResourceVersion second = f.publishSelectedFixture(c, "second", 0, 1, 1);
            assertEquals(status(c, first).budgetRefs(), status(c, second).budgetRefs());
            assertEquals(1, count("arte_ai_account"));
            AiControlData.BudgetRules before = governance(c).getRules(actor(), scope()).configuration();
            AiControlData.WorkspaceView view = governance(c).get(actor(), scope());
            governance(c).update(actor(), meta("deselect-first"), scope(), view.workspaceVersion(), view.configuration().version(), true,
                    List.of(new ModelSelection(second.resource().resourceId(), second.publication().binding().definition())));
            assertEquals("MODEL_NOT_ENABLED", status(c, first).reason());
            assertEquals(ModelUsagePreparation.State.READY, status(c, second).state());
            assertEquals(before, governance(c).getRules(actor(), scope()).configuration());
            assertEquals(1, count("arte_ai_account"));
            assertNotEquals(firstModel.resource(), second.resource());
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"RULE", "SELECTION"})
    void currentDispatchAndExternalSendDenyAfterRuleOrSelectionRevocation(String change) {
        f.context().run(c -> {
            f.prepare(c);
            ModelResourceVersion version = f.publishSelectedFixture(c, "publish", 0, 1, 1);
            com.arte.ainew.pojo.execution.Invocation invocation = f.accept(c, version, 128);
            AiAdmissionAuthorizationGuard guard = c.getBean(AiAdmissionAuthorizationGuard.class);
            AiAdmissionAuthorizationGuard.Permit permit = guard.authorize(f.check(c, invocation, version, AiAdmissionAuthorizationGuard.Boundary.DISPATCH)).block(f.WAIT);
            String ledger = f.f.f.f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_account", String.class);
            if (change.equals("RULE")) {
                rules(c, List.of());
            } else {
                AiControlData.WorkspaceView view = governance(c).get(actor(), scope());
                governance(c).update(actor(), meta("deselect"), scope(), view.workspaceVersion(), view.configuration().version(), true, List.of());
            }
            assertThrows(RuntimeException.class, () -> f.f.f.f.tx.required(() -> {
                permit.requireInCurrentTransaction(invocation.request());
                return null;
            }));
            assertThrows(RuntimeException.class, () -> guard.authorize(f.check(c, invocation, version, AiAdmissionAuthorizationGuard.Boundary.EXTERNAL_SEND)).block(f.WAIT));
            assertThrows(RuntimeException.class, () -> f.accept(c, version, 128));
            assertThrows(RuntimeException.class, () -> DatabaseAiTestCatalog.prepareOwn(c, actor(), scope(), version.resource().resourceId(), version.publication().binding().definition()));
            assertEquals(ledger, f.f.f.f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_account", String.class));
            assertEquals(0, count("arte_ai_attempt"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"AUDIT", "OUTBOX", "RECEIPT"})
    void ruleAndExistingAccountAdjustmentRollbackTogetherWithEpochAndReceipt(String failure) {
        f.context().run(c -> {
            f.prepare(c);
            f.publishSelectedFixture(c, "publish", 0, 1, 1);
            WorkspaceAiGovernanceService service = governance(c);
            AiControlData.BudgetRulesView before = service.getRules(actor(), scope());
            String ledger = f.f.f.f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_account", String.class);
            Object effects = ReflectionTestUtils.getField(service, "effects");
            if (failure.equals("AUDIT")) {
                ReflectionTestUtils.setField(effects, "audit", (AuditSink) entry -> {
                    throw new IllegalStateException("injected");
                });
            }
            if (failure.equals("OUTBOX")) {
                ReflectionTestUtils.setField(effects, "outbox", (PublicOutboxStore) entry -> {
                    throw new IllegalStateException("injected");
                });
            }
            if (failure.equals("RECEIPT")) {
                Object executor = ReflectionTestUtils.getField(service, "commands");
                com.arte.core.spi.platform.CommandReceiptStore original = (com.arte.core.spi.platform.CommandReceiptStore) ReflectionTestUtils.getField(executor, "receipts");
                Object proxy = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{com.arte.core.spi.platform.CommandReceiptStore.class},
                        (object, method, arguments) -> {
                            if (method.getName().equals("insertIfAbsent")) {
                                throw new IllegalStateException("injected");
                            }
                            return method.invoke(original, arguments);
                        });
                ReflectionTestUtils.setField(executor, "receipts", proxy);
            }
            AiControlData.BudgetPreset old = before.configuration().rules().getFirst();
            AiControlData.BudgetPreset lower = new AiControlData.BudgetPreset(old.rate(), new com.arte.ainew.pojo.budget.Money(new java.math.BigDecimal("50"), old.limit().currency()));
            int receipts = count("arte_platform_command"), events = count("arte_platform_outbox"), audits = count("arte_platform_audit");
            assertThrows(RuntimeException.class, () -> service.updateRules(actor(), meta("rollback"), scope(), before.workspaceVersion(), before.configuration().version(), List.of(lower)));
            assertEquals(before, service.getRules(actor(), scope()));
            assertEquals(ledger, f.f.f.f.jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_account", String.class));
            assertEquals(receipts, count("arte_platform_command"));
            assertEquals(events, count("arte_platform_outbox"));
            assertEquals(audits, count("arte_platform_audit"));
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"RULE", "VERSION", "CHILD"})
    void inFlightSettlementVersionSwitchRegenerationAndGenerationChildrenUseCurrentGovernance(String change) {
        f.loopback(false, change);
    }

    @Test
    void platformCapabilitiesFollowCurrentRbacWithoutScopeOrCatalogResponse() {
        f.context().run(c -> {
            var controller = new WorkspaceAiConfigurationController(c.getBean(DatabaseAiControlService.class),
                    c.getBean(RbacIdentityProvider.class), c.getBean(PlatformPersistenceExecutor.class), governance(c));
            try (var http = new ManagementHttpHarness(controller)) {
                String route = "/workspace/ai/getConfigurationCapabilities";
                var login = ManagementHttpHarness.login(f.f.f.f.jdbc, 1, "alice");
                var allowed = http.ok(route, "{}", null, login).getJSONObject("data");
                assertEquals(Set.of("available", "manageConnections", "managePlans", "manageDefaultTemplate"), allowed.keySet());
                assertTrue(allowed.getBool("manageConnections"));
                assertTrue(allowed.getBool("managePlans"));
                assertTrue(allowed.getBool("manageDefaultTemplate"));
                f.f.f.f.jdbc.update("DELETE FROM arte_rbac_relation WHERE target='aiConfiguration:manage'");
                var denied = http.ok(route, "{}", null, login).getJSONObject("data");
                assertTrue(denied.getBool("available"));
                assertFalse(denied.getBool("manageConnections"));
                assertFalse(denied.getBool("managePlans"));
                assertFalse(denied.getBool("manageDefaultTemplate"));
                http.rejected(route, "{\"manageConnections\":true}", null, login, 400, "INVALID_ARGUMENT");
                http.rejected(route, "{}", null, null, 401, "UNAUTHENTICATED");
            }
        });
    }

    @Test
    void httpRejectsFormerPresetBareBindingsForgedBudgetAndWrongResourceVersion() {
        f.context().run(c -> {
            f.prepare(c);
            ModelResourceVersion version = publishOnly(c, "publish", 0, 1);
            WorkspaceAiConfigurationController controller = new WorkspaceAiConfigurationController(c.getBean(DatabaseAiControlService.class), c.getBean(RbacIdentityProvider.class),
                    c.getBean(PlatformPersistenceExecutor.class), governance(c));
            try (ManagementHttpHarness http = new ManagementHttpHarness(controller)) {
                org.springframework.security.core.Authentication login = ManagementHttpHarness.login(f.f.f.f.jdbc, 1, "alice");
                AiControlData.WorkspaceView view = governance(c).get(actor(), scope());
                Map<String, Object> request = new HashMap<>(Map.of("scope", scope(), "expectedWorkspaceVersion", view.workspaceVersion(),
                        "expectedVersion", view.configuration().version(), "enabled", true, "models", List.of(new ModelSelection("wrong-resource", version.publication().binding().definition()))));
                http.rejected("/workspace/ai/updateConfiguration", f.f.f.f.json.write(request), "mismatch", login, 403, "ACCESS_DENIED");
                request.put("models", List.of());
                for (String forbidden : List.of("budgets", "bindings", "preset", "subjectId", "canRaiseBudget")) {
                    Map<String, Object> forged = new HashMap<>(request);
                    forged.put(forbidden, List.of());
                    assertEquals(400, http.execute("/workspace/ai/updateConfiguration", f.f.f.f.json.write(forged), "forged-" + forbidden, login).getResponse().getStatus());
                }
                String response = http.ok("/workspace/ai/getBudgetRules", f.f.f.f.json.write(Map.of("scope", scope())), null, login).toString();
                assertFalse(response.contains("subjectId"));
                assertFalse(response.contains("budgetRef"));
                assertFalse(response.contains("credential"));
                assertEquals(0, count("arte_ai_account"));
            }
        });
    }
}
