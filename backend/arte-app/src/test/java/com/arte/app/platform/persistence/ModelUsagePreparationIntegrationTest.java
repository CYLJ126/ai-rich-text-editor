package com.arte.app.platform.persistence;

import com.arte.ainew.api.model.ModelDraftService;
import com.arte.ainew.api.model.ModelResourceService;
import com.arte.ainew.api.model.ModelUsagePreparationService;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.model.ModelResourceCommands;
import com.arte.ainew.pojo.model.ModelResourceVersion;
import com.arte.ainew.pojo.model.ModelUsagePreparation;
import com.arte.app.platform.ai.AiControlData;
import com.arte.app.platform.ai.DatabaseModelUsagePreparationService;
import com.arte.app.platform.identity.PlatformAuthenticatedExecutor;
import com.arte.app.web.controller.platform.ModelUsagePreparationController;
import com.arte.core.enums.platform.CommandDeliveryEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.platform.CommandMeta;
import com.arte.core.pojo.platform.CommandReceipt;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.spi.platform.AuditSink;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实公共身份、H2/MySQL Mapper、ACL、同库命令回执及严格 MVC；不调用外部模型或业务数据库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/02 21:30 ✾
 */
class ModelUsagePreparationIntegrationTest {

    final PersonalModelPublicationIntegrationTest f = new PersonalModelPublicationIntegrationTest();
    ModelResourceVersion version;
    final JacksonExecutionRecordCodec codec = new JacksonExecutionRecordCodec();

    @BeforeEach
    void setup() throws Exception {
        f.setup();
    }

    @AfterEach
    void cleanup() {
        f.cleanup();
    }

    JdbcTemplate jdbc() {
        return f.f.f.f.jdbc;
    }

    Scope scope() {
        return f.f.scope;
    }

    CommandMeta meta(String key) {
        return new CommandMeta(key, "a1-test");
    }

    DefinitionRef binding() {
        return version.publication().binding().definition();
    }

    ModelUsagePreparationService service(ApplicationContext c) {
        return c.getBean(ModelUsagePreparationService.class);
    }

    void prepare(ApplicationContext c) {
        f.prepare(c);
        version = f.publish(c, "publish-a1", 0, 1, 1);
    }

    void removeAccounts() {
        jdbc().update("DELETE FROM arte_ai_account");
        jdbc().update("DELETE FROM arte_ai_budget_definition");
    }

    int count(String table) {
        return jdbc().queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    ModelUsagePreparation status(ApplicationContext c) {
        return service(c).status(f.f.f.f.actor, scope(), f.model.resource(), binding());
    }

    CommandReceipt<ModelUsagePreparation> run(ApplicationContext c, String key) {
        return service(c).prepare(f.f.f.f.actor, meta(key), scope(), f.model.resource(), binding());
    }

    AiControlData.WorkspaceConfiguration config() {
        return f.f.f.f.json.read(jdbc().queryForObject("SELECT payload FROM arte_ai_workspace_configuration WHERE tenant_id=? AND workspace_id=?",
                String.class, scope().tenantId(), scope().workspaceId()), AiControlData.WorkspaceConfiguration.class);
    }

    void preset(AiControlData.Preset preset) {
        var old = config();
        var next = new AiControlData.WorkspaceConfiguration(scope(), preset, old.templateVersion(), old.version() + 1);
        jdbc().update("UPDATE arte_ai_workspace_configuration SET payload=?,version=? WHERE tenant_id=? AND workspace_id=?",
                f.f.f.f.json.write(next), next.version(), scope().tenantId(), scope().workspaceId());
    }

    void denied(Runnable action, PlatformErrorCodeEnum code) {
        assertEquals(code, assertThrows(PlatformException.class, action::run).getCode());
    }

    @Test
    void statusDoesNotWriteAndOrdinaryUserPreparesOnlyOnceAcrossKeysAndReassembly() {
        f.context().run(c -> {
            prepare(c);
            removeAccounts();
            int commands = count("arte_platform_command"), events = count("arte_platform_outbox");
            assertEquals(ModelUsagePreparation.State.PREPARABLE, status(c).state());
            assertEquals(0, count("arte_ai_budget_definition"));
            assertEquals(commands, count("arte_platform_command"));
            assertEquals(events, count("arte_platform_outbox"));
            var first = run(c, "first");
            assertEquals(ModelUsagePreparation.State.READY, first.value().state());
            assertEquals(1, count("arte_ai_budget_definition"));
            assertEquals(1, count("arte_ai_account"));
            var replay = run(c, "first");
            assertEquals(first.operationId(), replay.operationId());
            assertEquals(CommandDeliveryEnum.REPLAYED, replay.delivery());
            assertEquals(first.value(), replay.value());
            assertEquals(first.value().budgetRefs(), run(c, "another-key").value().budgetRefs());
            assertEquals(1, count("arte_ai_account"));
        });
        // 真正重新装配，回执通过持久化 Schema 白名单解码，状态使用当前事实。
        f.context().run(c -> {
            assertEquals(CommandDeliveryEnum.REPLAYED, run(c, "first").delivery());
            assertEquals(ModelUsagePreparation.State.READY, status(c).state());
        });
    }

    @Test
    void existingHeldChargedLimitAndVersionAreNeverResetOrIncreased() {
        f.context().run(c -> {
            prepare(c);
            String encoded = jdbc().queryForObject("SELECT workspace_snapshot FROM arte_ai_account", String.class);
            var old = codec.decode(encoded, BudgetCommands.Account.class);
            Money held = new Money(new BigDecimal("2"), old.limit().currency());
            Money charged = new Money(new BigDecimal("3"), old.limit().currency());
            String spent = codec.encode(new BudgetCommands.Account(old.budgetRef(), old.owner(), old.limit(), held, charged, old.rateVersion(), 19));
            jdbc().update("UPDATE arte_ai_account SET workspace_snapshot=?", spent);
            assertEquals(ModelUsagePreparation.State.READY, run(c, "existing").value().state());
            run(c, "existing");
            run(c, "existing-new-key");
            assertEquals(spent, jdbc().queryForObject("SELECT workspace_snapshot FROM arte_ai_account", String.class));
        });
    }

    @Test
    void missingRuleDisabledAiAndZeroAllocationDoNotCreateAccounts() {
        f.context().run(c -> {
            prepare(c);
            removeAccounts();
            var original = config().preset();
            preset(new AiControlData.Preset(true, List.of(), List.of()));
            assertEquals("BUDGET_RULE_NOT_CONFIGURED", status(c).reason());
            denied(() -> run(c, "no-rule"), PlatformErrorCodeEnum.ACCESS_DENIED);
            preset(new AiControlData.Preset(false, original.bindings(), original.budgets()));
            assertEquals("WORKSPACE_AI_DISABLED", status(c).reason());
            denied(() -> run(c, "disabled-ai"), PlatformErrorCodeEnum.ACCESS_DENIED);
            var rule = original.budgets().getFirst();
            preset(new AiControlData.Preset(true, original.bindings(), List.of(new AiControlData.BudgetPreset(rule.rate(), new Money(BigDecimal.ZERO, rule.limit().currency())))));
            assertEquals("BUDGET_RULE_ZERO", status(c).reason());
            denied(() -> run(c, "zero"), PlatformErrorCodeEnum.ACCESS_DENIED);
            assertEquals(0, count("arte_ai_account"));
            assertEquals(0, count("arte_ai_budget_definition"));
        });
    }

    @Test
    void missingOrCorruptLedgerFailsClosedRatherThanReinitializing() {
        f.context().run(c -> {
            prepare(c);
            jdbc().update("DELETE FROM arte_ai_account");
            denied(() -> status(c), PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            denied(() -> run(c, "missing-ledger"), PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            assertEquals(0, count("arte_ai_account"));
        });
    }

    @Test
    void unpreparedSpaceUsesApprovedTemplateWithoutPreparingOtherRates() {
        f.context().run(c -> {
            prepare(c);
            removeAccounts();
            jdbc().update("DELETE FROM arte_ai_workspace_configuration WHERE tenant_id=? AND workspace_id=?", scope().tenantId(), scope().workspaceId());
            assertEquals(ModelUsagePreparation.State.PREPARABLE, status(c).state());
            assertEquals(0, count("arte_ai_workspace_configuration"));
            assertEquals(ModelUsagePreparation.State.READY, run(c, "prepare-space").value().state());
            assertEquals(1, count("arte_ai_workspace_configuration"));
            assertEquals(1, count("arte_ai_account"));
        });
    }

    @Test
    void revokedCredentialAndDisabledModelCannotPrepareOrReplayOldSuccess() {
        f.context().run(c -> {
            prepare(c);
            run(c, "before-revocation");
            c.getBean(ModelDraftService.class).revokeCredential(f.f.f.f.actor, meta("revoke"), scope(), f.model.resource(), 1);
            assertEquals("CREDENTIAL_STALE", status(c).reason());
            denied(() -> run(c, "before-revocation"), PlatformErrorCodeEnum.ACCESS_DENIED);
            denied(() -> run(c, "after-revocation"), PlatformErrorCodeEnum.ACCESS_DENIED);
            c.getBean(ModelResourceService.class).disable(f.f.f.f.actor, new ModelResourceCommands.Disable(meta("disable"), scope(), f.model.resource(), 1));
            assertThrows(PlatformException.class, () -> status(c));
        });
    }

    @Test
    void auditFailureRollsBackAccountDefinitionWorkspaceAndCommandTogether() {
        f.context().run(c -> {
            prepare(c);
            removeAccounts();
            jdbc().update("DELETE FROM arte_ai_workspace_configuration WHERE tenant_id=? AND workspace_id=?", scope().tenantId(), scope().workspaceId());
            Object effects = ReflectionTestUtils.getField(c.getBean(DatabaseModelUsagePreparationService.class), "effects");
            ReflectionTestUtils.setField(effects, "audit", (AuditSink) entry -> {
                throw new IllegalStateException("audit unavailable");
            });
            int commands = count("arte_platform_command"), events = count("arte_platform_outbox");
            assertThrows(RuntimeException.class, () -> run(c, "rollback"));
            assertEquals(0, count("arte_ai_account"));
            assertEquals(0, count("arte_ai_budget_definition"));
            assertEquals(0, count("arte_ai_workspace_configuration"));
            assertEquals(commands, count("arte_platform_command"));
            assertEquals(events, count("arte_platform_outbox"));
        });
    }

    @Test
    void concurrentDifferentKeysAllocateOneAccountAndOneDefinition() {
        f.context().run(c -> {
            prepare(c);
            removeAccounts();
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService pool = Executors.newFixedThreadPool(4)) {
                List<Future<CommandReceipt<ModelUsagePreparation>>> futures = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    String key = "parallel-" + i;
                    futures.add(pool.submit(() -> {
                        start.await();
                        return run(c, key);
                    }));
                }
                start.countDown();
                for (var future : futures)
                    assertEquals(ModelUsagePreparation.State.READY, future.get(20, TimeUnit.SECONDS).value().state());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            assertEquals(1, count("arte_ai_account"));
            assertEquals(1, count("arte_ai_budget_definition"));
        });
    }

    @Test
    void concurrentSameKeyReturnsOneReceiptAndDifferentContentCannotReuseIt() {
        f.context().run(c -> {
            prepare(c);
            removeAccounts();
            CountDownLatch start = new CountDownLatch(1);
            try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
                List<Future<CommandReceipt<ModelUsagePreparation>>> futures = new ArrayList<>();
                for (int i = 0; i < 3; i++)
                    futures.add(pool.submit(() -> {
                        start.await();
                        return run(c, "same-key");
                    }));
                start.countDown();
                String operation = futures.getFirst().get(20, TimeUnit.SECONDS).operationId();
                for (var future : futures) assertEquals(operation, future.get(20, TimeUnit.SECONDS).operationId());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
            assertEquals(1, count("arte_ai_account"));
            denied(() -> service(c).prepare(f.f.f.f.actor, meta("same-key"), scope(), f.model.resource(),
                    new DefinitionRef("binding", binding().id(), "v999")), PlatformErrorCodeEnum.IDEMPOTENCY_CONFLICT);
        });
    }

    @Test
    void exactRateVersionIsRequiredAndPreparationDoesNotAllocateOtherApprovedRates() {
        f.context().run(c -> {
            prepare(c);
            removeAccounts();
            var original = config().preset();
            var rule = original.budgets().getFirst();
            var otherRate = new DefinitionRef("rate", rule.rate().id(), "other-version");
            // 旧费率规则不能用于新版本，即便币种与金额相同。
            preset(new AiControlData.Preset(true, List.of(), List.of(new AiControlData.BudgetPreset(otherRate, rule.limit()))));
            assertEquals("BUDGET_RULE_NOT_CONFIGURED", status(c).reason());
            denied(() -> run(c, "wrong-rate"), PlatformErrorCodeEnum.ACCESS_DENIED);
            // 多项已批准规则中，只为当前模型的精确费率准备账户。
            preset(new AiControlData.Preset(true, original.bindings(), List.of(rule, new AiControlData.BudgetPreset(otherRate, rule.limit()))));
            assertEquals(ModelUsagePreparation.State.READY, run(c, "selected-rate").value().state());
            assertEquals(1, count("arte_ai_account"));
            assertEquals(1, count("arte_ai_budget_definition"));
        });
    }

    @Test
    void orphanedLedgerAndChangedScopeCannotCreateASecondBudget() {
        f.context().run(c -> {
            prepare(c);
            jdbc().update("DELETE FROM arte_ai_budget_definition");
            denied(() -> run(c, "orphaned"), PlatformErrorCodeEnum.AUTHORITY_UNAVAILABLE);
            assertEquals(1, count("arte_ai_account"));
            assertEquals(0, count("arte_ai_budget_definition"));
            assertThrows(PlatformException.class, () -> service(c).status(f.f.f.f.actor,
                    new Scope(scope().tenantId(), "another-space"), f.model.resource(), binding()));
        });
    }

    @Test
    void unpreparedSpaceWithExistingLedgerIsPreparedWithoutChangingTheLedger() {
        f.context().run(c -> {
            prepare(c);
            String encoded = jdbc().queryForObject("SELECT workspace_snapshot FROM arte_ai_account", String.class);
            jdbc().update("DELETE FROM arte_ai_workspace_configuration WHERE tenant_id=? AND workspace_id=?", scope().tenantId(), scope().workspaceId());
            assertEquals(ModelUsagePreparation.State.PREPARABLE, status(c).state());
            assertEquals(ModelUsagePreparation.State.READY, run(c, "existing-space").value().state());
            assertEquals(1, count("arte_ai_workspace_configuration"));
            assertEquals(encoded, jdbc().queryForObject("SELECT workspace_snapshot FROM arte_ai_account", String.class));
        });
    }

    @Test
    void explicitlyMigratedDatabaseModelAlsoUsesApprovedExactRateWithoutLegacyFallback() {
        f.context().run(c -> {
            var adopted = f.f.prepare(c);
            removeAccounts();
            var exact = f.f.f.ref("binding", "text");
            var usage = service(c).prepare(f.f.f.f.actor, meta("adopted-model"), scope(), adopted.resource(), exact);
            assertEquals(ModelUsagePreparation.State.READY, usage.value().state());
            assertEquals(1, count("arte_ai_account"));
        });
    }

    @Test
    void preparedBudgetIsDiscoveredAndUsedByExistingChatAdmissionWithoutLedgerRewrite() {
        f.context().run(c -> {
            prepare(c);
            removeAccounts();
            var result = run(c, "prepare-for-chat").value();
            var execution = f.f.execution(c, "discover-prepared");
            var option = c.getBean(com.arte.ainew.application.control.ChatConfigurationQueryService.class).discover(execution)
                    .block(PersonalModelPublicationIntegrationTest.WAIT).options().stream()
                    .filter(value -> value.binding().equals(binding())).findFirst().orElseThrow();
            assertEquals(result.budgetRefs(), option.budgetRefs());
            assertEquals(com.arte.ainew.pojo.execution.Invocation.State.ACCEPTED, f.accept(c, version, 128).state());
            String reserved = jdbc().queryForObject("SELECT workspace_snapshot FROM arte_ai_account", String.class);
            run(c, "prepare-for-chat-again");
            assertEquals(reserved, jdbc().queryForObject("SELECT workspace_snapshot FROM arte_ai_account", String.class));
        });
    }

    @Test
    void strictHttpRejectsSelfReportedMoneyIdentityRatesUnknownFieldsAndMissingKey() {
        f.context().run(c -> {
            prepare(c);
            removeAccounts();
            var executor = c.getBean(PlatformAuthenticatedExecutor.class);
            try (var http = new ManagementHttpHarness(new ModelUsagePreparationController(executor, service(c)))) {
                var auth = ManagementHttpHarness.login(jdbc(), 1, "alice");
                Map<String, Object> request = Map.of("scope", scope(), "resourceId", f.model.resource().resourceId(), "binding", binding());
                String prefix = "/ai-new/model-resource/";
                String body = f.f.f.f.json.write(request);
                assertEquals("PREPARABLE", http.ok(prefix + "getModelUsagePreparation", body, null, auth).getJSONObject("data").getStr("state"));
                for (String field : List.of("amount", "subjectId", "rate", "budgetRef", "endpoint")) {
                    var forged = new java.util.HashMap<>(request);
                    forged.put(field, "forged");
                    assertEquals(400, http.execute(prefix + "prepareModelUsage", f.f.f.f.json.write(forged), "forged-" + field, auth).getResponse().getStatus());
                }
                assertEquals(400, http.execute(prefix + "prepareModelUsage", body, null, auth).getResponse().getStatus());
                var response = http.ok(prefix + "prepareModelUsage", body, "http-ready", auth).getJSONObject("data");
                assertEquals("READY", response.getJSONObject("value").getStr("state"));
                assertFalse(response.toString().contains("credential"));
                assertFalse(response.toString().contains("endpoint"));
            }
        });
    }
}
