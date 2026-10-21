package com.arte.app.platform.persistence;

import com.arte.ainew.application.execution.InvocationDispatchWorker;
import com.arte.ainew.application.tool.discovery.StaticToolDiscovery;
import com.arte.ainew.application.tool.discovery.StaticToolRegistry;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.spi.adapter.PaidToolAdapter;
import com.arte.ainew.spi.adapter.StaticToolDefinitionSource;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import com.arte.app.platform.ai.AiControlData;
import com.arte.app.platform.ai.DatabaseAiControlService;
import com.arte.app.platform.ai.tool.LocalTextTools;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.*;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 正式平台当前权限、目录批准、空间预算及公开 HTTP；计费适配只在测试显式登记。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 14:00 ✾
 */
class PlatformToolBillingIntegrationTest {

    static DefinitionRef ref(String type, String id) {
        return new DefinitionRef(type, id, "v1");
    }

    static Money money(String value) {
        return new Money(new BigDecimal(value), Currency.getInstance("CNY"));
    }

    static final ToolPrice PRICE = new ToolPrice(ref("rate", "paid-count-rate"), money("0.25"));

    static final class Contract implements PaidToolAdapter {
        final LocalTextTools text;
        final ToolDefinitionRevision revision;

        Contract(LocalTextTools text) {
            this.text = text;
            var base = text.revision().tool();
            var unsigned = new ToolDefinition(ref("tool", "arte.paid.count"), base.providerId(), "按次收费测试计数", "测试登记的受控计数；每次报价上界 0.25 CNY", base.tags(), ref("capability", "paid-count"),
                    base.inputSchema(), base.outputSchema(), base.configurationSchema(), base.defaultConfiguration(), base.capabilities(), base.risk(), base.defaultPolicy(), "0".repeat(64), PRICE);
            var signed = new ToolDefinition(unsigned.definition(), unsigned.providerId(), unsigned.title(), unsigned.description(), unsigned.tags(), unsigned.capability(), unsigned.inputSchema(), unsigned.outputSchema(), unsigned.configurationSchema(),
                    unsigned.defaultConfiguration(), unsigned.capabilities(), unsigned.risk(), unsigned.defaultPolicy(), ToolCatalogDigests.tool(unsigned), PRICE);
            revision = new ToolDefinitionRevision(signed, text.revision().provider(), new ExecutionTarget.Local(ref("tool-adapter", "paid-count")));
        }

        public ToolDefinitionRevision revision() {
            return revision;
        }

        public com.arte.ainew.pojo.control.CapabilityDescriptor capability() {
            return revision.tool().discoveredCapability();
        }

        public void requireCharge(ToolAdmissionInput input, ExecutionContext context, ToolBindingResolution binding) {
            if (!binding.tool().equals(revision) || input.budgetRef() == null || !binding.effectiveConfiguration().equals(revision.tool().defaultConfiguration()))
                throw new AccessDeniedException("Paid test contract mismatch");
        }

        public Mono<ToolResult> invoke(GatewayCall<ToolInvocation> call) {
            var value = ((StructuredValue.StringValue) call.request().input().arguments().fields().get("text")).value();
            return Mono.just(new ToolResult(call.request().input().callId(), ToolResult.Outcome.SUCCEEDED, new StructuredValue.ObjectValue(Map.of("codePoints", new StructuredValue.NumberValue(BigDecimal.valueOf(value.codePointCount(0, value.length()))))),
                    List.of(), List.of(), ExecutionError.SideEffect.NONE, null, new ToolCharge(PRICE.perCall(), "test-invoice-" + call.attempt().attemptId())));
        }

        StaticToolRegistry registry() {
            var local = text.registry().registrations().getFirst();
            var adapters = new HashMap<>(local.localAdapters());
            adapters.put(((ExecutionTarget.Local) revision.target()).adapter(), this);
            var definitions = new StaticToolDefinitionSource.Definitions(text.definitions().schemas(), List.of(text.revision(), revision));
            return new StaticToolRegistry(List.of(new StaticToolRegistry.Registration(local.provider(), () -> Mono.just(definitions), adapters)));
        }

        String manifest() {
            return StaticToolDiscovery.manifest(new ToolCatalogStore.Batch(List.of(text.registry().registrations().getFirst().provider()), text.definitions().schemas(), List.of(text.revision(), revision)));
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class Registration {
        @Bean
        Contract paidContract(LocalTextTools text) {
            return new Contract(text);
        }

        @Bean
        @Primary
        StaticToolRegistry paidRegistry(Contract contract) {
            return contract.registry();
        }
    }

    void prepareBudget(ToolAcceptanceRig rig, org.springframework.context.ApplicationContext c) {
        var db = c.getBean(DatabaseAiControlService.class);
        db.importConfiguration(rig.base.f.actor, rig.base.meta("import"));
        var old = db.catalog().catalog();
        var rates = new ArrayList<>(old.rates());
        rates.add(new NewAiProperties.Rate(PRICE.rate(), null, null, PRICE.perCall()));
        var preset = new AiControlData.Preset(true, List.of(), List.of(new AiControlData.BudgetPreset(PRICE.rate(), money("10"))));
        db.publish(rig.base.f.actor, rig.base.meta("publish-tool-price"), 1, new AiControlData.Catalog(old.capabilities(), old.bindings(), old.connections(), rates, old.legacyGrants(), old.legacyBudgets(), preset));
        var current = db.get(rig.base.f.actor, rig.scope);
        db.update(rig.base.f.actor, rig.base.meta("approve-tool-budget"), rig.scope, current.workspaceVersion(), current.configuration().version(), preset, true);
    }

    String publishAndBind(ToolAcceptanceRig rig, org.springframework.test.web.servlet.MockMvc mvc, Contract contract) throws Exception {
        var review = Map.of("examples", List.of(Map.of("input", Map.of("text", "abc"), "output", Map.of("codePoints", 3))), "riskEvidence", "Reviewed per-call price and trusted invoice; no external data", "minimumApproval", "NONE");
        rig.post(mvc, "tool/publishTool", Map.of("scope", rig.scope, "tool", contract.revision.tool().definition(), "expectedVersion", "0", "reason", "billing review", "review", review), "publish-paid", 200);
        return rig.post(mvc, "tool/prepareDefaultBinding", Map.of("scope", rig.scope, "tool", contract.revision.tool().definition(), "configuration", Map.of()), "bind-paid", 200).get("bindingId").asString();
    }

    @Test
    void paidPublicFlowUsesApprovedToolAccountAndCurrentRbac() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            var metadata = new Contract(new LocalTextTools(Clock.systemUTC()));
            rig.context().withUserConfiguration(Registration.class).withPropertyValues("arte.ai-new-tool-billing.enabled=true", "arte.ai-new-execution.scheduling.tool-manifest=" + metadata.manifest()).run(c -> {
                assertNull(c.getStartupFailure());
                prepareBudget(rig, c);
                var mvc = rig.mvc(c);
                var binding = publishAndBind(rig, mvc, c.getBean(Contract.class));
                var options = rig.post(mvc, "tool/availableToolBudgets", Map.of("scope", rig.scope, "bindingId", binding), null, 200);
                assertEquals("0.25", options.get("price").get("perCall").get("amount").asString());
                assertEquals(1, options.get("accounts").size());
                String budget = options.get("accounts").get(0).get("budgetRef").asString();
                var input = Map.of("bindingId", binding, "arguments", Map.of("text", "abc"), "timeoutSeconds", 30, "budgetRef", budget);
                rig.post(mvc, "tool/previewInvocation", Map.of("scope", rig.scope, "input", Map.of("bindingId", binding, "arguments", Map.of("text", "abc"))), null, 409);
                var preview = rig.post(mvc, "tool/previewInvocation", Map.of("scope", rig.scope, "input", input), null, 200);
                assertEquals("CHARGE", preview.get("billingMode").asString());
                assertEquals(budget, preview.get("budgetRef").asString());
                var command = Map.of("scope", rig.scope, "input", input, "expectedPlanDigest", preview.get("planDigest").asString());
                String id = rig.post(mvc, "tool/invokeTool", command, "paid-one", 202).get("executionId").asString();
                c.getBean(InvocationDispatchWorker.class).pollOnce().block(Duration.ofSeconds(15));
                assertEquals("SUCCEEDED", rig.post(mvc, "invocation/getInvocationStatus", Map.of("scope", rig.scope, "invocationId", id), null, 200).get("state").asString());
                assertEquals(1, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_bill", Integer.class));
                assertEquals(1, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_reservation", Integer.class));
                assertEquals(id, rig.post(mvc, "tool/invokeTool", command, "paid-one", 202).get("executionId").asString());
                String stopped = rig.post(mvc, "tool/invokeTool", command, "paid-two", 202).get("executionId").asString();
                rig.base.f.jdbc.update("DELETE FROM arte_rbac_relation WHERE target='aiTool:invoke'");
                c.getBean(InvocationDispatchWorker.class).pollOnce().block(Duration.ofSeconds(15));
                assertEquals("FAILED", rig.post(mvc, "invocation/getInvocationStatus", Map.of("scope", rig.scope, "invocationId", stopped), null, 200).get("state").asString());
                assertEquals(1, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_reservation", Integer.class));
                rig.post(mvc, "tool/invokeTool", command, "paid-one", 403);
            });
        }
    }

    @Test
    void billingFlagIsClosedForApprovedPaidTools() throws Exception {
        try (var rig = new ToolAcceptanceRig()) {
            var metadata = new Contract(new LocalTextTools(Clock.systemUTC()));
            rig.context().withUserConfiguration(Registration.class).withPropertyValues("arte.ai-new-execution.scheduling.tool-manifest=" + metadata.manifest()).run(c -> {
                assertNull(c.getStartupFailure());
                prepareBudget(rig, c);
                var mvc = rig.mvc(c);
                var binding = publishAndBind(rig, mvc, c.getBean(Contract.class));
                rig.post(mvc, "tool/previewInvocation", Map.of("scope", rig.scope, "input", Map.of("bindingId", binding, "arguments", Map.of("text", "abc"), "budgetRef", "unapproved")), null, 403);
                assertEquals(0, rig.base.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_reservation", Integer.class));
            });
        }
    }
}
