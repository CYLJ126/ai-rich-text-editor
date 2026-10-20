package com.arte.ainew.tool.admission;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver;
import com.arte.ainew.application.control.FixedControlCatalog;
import com.arte.ainew.application.tool.chat.ToolChatBudgetSelector;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.persistence.mybatis.MybatisToolChatRunStore;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.context.ContextBudget;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ConnectionDefinition;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationOptions;
import com.arte.ainew.pojo.generation.ToolCall;
import com.arte.ainew.pojo.tool.*;
import org.junit.Test;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.Currency;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.arte.ainew.pojo.tool.ToolChatRun.Phase.WAITING_MODEL;
import static com.arte.ainew.pojo.tool.ToolChatRun.Phase.WAITING_TOOLS;
import static com.arte.ainew.tool.discovery.LocalToolFixtures.object;
import static org.junit.Assert.*;

/**
 * 当前模型配置、真实本地工具发布和账户上的选择验证；选择器不创建或执行子调用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 20:20 ✾
 */
public class ToolChatBudgetSelectorTest {

    static final Duration WAIT = Duration.ofSeconds(15);

    static Money money(String value) {
        return new Money(new BigDecimal(value), Currency.getInstance("CNY"));
    }

    static DefinitionRef ref(String type, String id) {
        return new DefinitionRef(type, id, "v1");
    }

    static final class Rig implements AutoCloseable {
        final ToolAdmissionFixture f = new ToolAdmissionFixture();
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final MybatisToolChatRunStore runs = new MybatisToolChatRunStore(f.source, f.codec, f.io);
        final ToolChatBudgetSelector selector;
        final ExecutionContext context;
        final ToolChatRunSnapshot material;
        final ToolChatRunSnapshot fixed;
        final ToolChatStep step;

        Rig() throws Exception {
            var scopes = Set.of("ai:invoke", "ai:read", "ai:conversation", "resource:read");
            var auth = new ExecutionAuthorization(new ExecutionPrincipal("subject", "alice", ExecutionPrincipal.Kind.USER), "tenant", "workspace", scopes, "grant");
            var now = Instant.now();
            context = new ExecutionContext("run", "root-trace", auth, now.plusSeconds(90), null, null, "release", "submit");
            var cap = new CapabilityDescriptor(ref("capability", "model"), CapabilityDescriptor.Kind.GENERATION, FixedControlCatalog.INPUT_SCHEMA, FixedControlCatalog.OUTPUT_SCHEMA,
                    Set.of(CapabilityDescriptor.Feature.TEXT_INPUT, CapabilityDescriptor.Feature.STREAMING, CapabilityDescriptor.Feature.TOOL_CALLS),
                    CapabilityDescriptor.SideEffect.READ_ONLY, CapabilityDescriptor.Availability.EXECUTABLE);
            var model = new ResolvedBinding(ref("binding", "model"), cap, ref("connection", "model"), "model", 8192L, ref("rate", "model"));
            var rate = new NewAiProperties.Rate(model.rate(), money("1"), money("2"));
            var grant = new NewAiProperties.Grant("alice", "subject", ExecutionPrincipal.Kind.USER, "tenant", "workspace", "grant", true,
                    scopes, Set.of("model"), Set.of("wallet"));
            var connection = new ConnectionDefinition(model.connection(), "provider", ref("protocol", "http"), URI.create("https://example.invalid/v1"), ref("secret", "model"),
                    ConnectionDefinition.State.ENABLED, Duration.ofSeconds(5), Duration.ofSeconds(90), 4096);
            var cfg = new NewAiProperties(true, "test", "release", null, null, List.of(grant), List.of(cap), List.of(model), List.of(connection), List.of(rate),
                    List.of(new NewAiProperties.Budget("wallet", f.owner, money("100"), model.rate())), "FIXED");
            var base = new FixedExecutionAuthorizationResolver(cfg);
            var authorization = new AdmissionAuthorization((name, tenant, workspace, required) -> allowed.get() ? base.resolve(name, tenant, workspace, required)
                    : Mono.error(new AccessDeniedException("revoked")), cfg, f.clock);
            var catalog = new FixedControlCatalog(cfg, authorization, f.clock);
            selector = new ToolChatBudgetSelector(runs, authorization, catalog, f.executions, f.bindingService, f.clock);
            f.executions.createAccount(new BudgetCommands.Account("wallet", f.owner, money("100"), money("0"), money("0"), model.rate(), 0)).block(WAIT);
            var binding = f.bindingService.resolve(f.owner, f.binding.bindingId()).block(WAIT).value();
            var schema = f.catalog.findSchema(binding.tool().tool().inputSchema()).block(WAIT);
            var tools = new ToolSetSnapshot(f.owner, List.of(new ToolSetSnapshot.Entry("tool_0", binding, binding.effectivePolicy(), schema)));
            var initial = new ContextSnapshot("context", null, model.definition(), List.of(new ChatMessage("user", ChatMessage.Role.USER,
                    List.of(new ChatMessage.Text("question")), List.of(), null)), List.of(), new ContextBudget(8192, 4096, 1024, 1024), 10, true, "tokenizer", List.of(), "0".repeat(64), now, now.plusSeconds(120));
            material = new ToolChatRunSnapshot(context, initial, model, new GenerationOptions(128, null, null, List.of()), tools,
                    new ToolChatRunSnapshot.Limits(Duration.ofSeconds(90), 3, 5, 1), now);
            var plan = selector.prepare(material, List.of(money("1")), "wallet", context).block(WAIT);
            fixed = new ToolChatRunSnapshot(context, initial, model, material.generation(), tools, material.limits(), now, plan);
            assertTrue(runs.accept(fixed, ignored -> {
            }).block(WAIT).successful());
            var lease = runs.claim("worker", 1, Duration.ofSeconds(30)).block(WAIT).getFirst();
            step = runs.advance(new ToolChatCommands.Advance(lease, 0, "model", ToolChatRun.State.RUNNING, WAITING_MODEL, 0,
                    List.of(new ToolChatStep.Plan(0, new ToolChatStep.Model(initial))), null, null, null), ignored -> {
            }).block(WAIT).value().steps().getFirst();
        }

        ExecutionContext model() {
            return selector.child("run", step.childExecutionId(), context.authorization().scopes(), context.deadline().plusSeconds(100), context).block(WAIT);
        }

        ToolChatStep tool() {
            runs.wake(f.owner, "run").block(WAIT);
            var lease = runs.claim("worker", 1, Duration.ofSeconds(30)).block(WAIT).getFirst();
            return runs.advance(new ToolChatCommands.Advance(lease, 1, "tool", ToolChatRun.State.WAITING_TOOL, WAITING_TOOLS, 0,
                            List.of(new ToolChatStep.Plan(0, new ToolChatStep.Tool(new ToolCall("call", f.v1.revision().tool().definition(), object())))), null, null, null), ignored -> {
                    })
                    .block(WAIT).value().steps().getFirst();
        }

        public void close() throws Exception {
            f.close();
        }
    }

    @Test
    public void modelGetsOnlyFixedAuthorizedAccountAndPreservesOwnerTraceParentDeadline() throws Exception {
        try (var r = new Rig()) {
            var child = r.model();
            assertNull(r.context.budgetRef());
            assertEquals("wallet", child.budgetRef());
            assertEquals("run", child.parentExecutionId());
            assertEquals("root-trace", child.traceId());
            assertEquals(r.context.deadline(), child.deadline());
            assertEquals(r.context.authorization(), child.authorization());
            assertEquals(child, r.model());
            assertEquals(0, r.f.count("arte_ai_invocation"));
            assertEquals(0, r.f.count("arte_ai_reservation"));
            assertThrows(RuntimeException.class, () -> r.selector.prepare(r.material, List.of(money("1")), "client-wallet", r.context).block(WAIT));
        }
    }

    @Test
    public void currentRevocationForeignOwnerWrongRateAndCurrencyCannotReuseFixedSelection() throws Exception {
        try (var r = new Rig()) {
            r.allowed.set(false);
            assertThrows(AccessDeniedException.class, r::model);
            r.allowed.set(true);
            var account = r.f.executions.account(r.f.owner, "wallet").block(WAIT);
            var wrong = new BudgetCommands.Account("wallet", r.f.owner, account.limit(), account.held(), account.charged(), ref("rate", "wrong"), 0);
            r.f.jdbc.update("UPDATE arte_ai_account SET workspace_snapshot=?", r.f.codec.encode(wrong));
            assertThrows(RuntimeException.class, r::model);
            var usd = new Money(BigDecimal.ONE, Currency.getInstance("USD"));
            var zero = new Money(BigDecimal.ZERO, usd.currency());
            wrong = new BudgetCommands.Account("wallet", r.f.owner, usd, zero, zero, account.rateVersion(), 0);
            r.f.jdbc.update("UPDATE arte_ai_account SET workspace_snapshot=?", r.f.codec.encode(wrong));
            assertThrows(RuntimeException.class, r::model);
            r.f.jdbc.update("UPDATE arte_ai_account SET workspace_snapshot=?", r.f.codec.encode(new BudgetCommands.Account("wallet", new ExecutionOwner("tenant", "workspace", "other"),
                    account.limit(), account.held(), account.charged(), account.rateVersion(), 0)));
            assertThrows(RuntimeException.class, r::model);
        }
    }

    @Test
    public void freeToolSelectsNullAccountWithoutModelLedgerAndStillChecksCurrentBinding() throws Exception {
        try (var r = new Rig()) {
            var step = r.tool();
            r.f.jdbc.update("DELETE FROM arte_ai_account");
            var child = r.selector.child("run", step.childExecutionId(), r.context.authorization().scopes(), r.context.deadline(), r.context).block(WAIT);
            assertNull(child.budgetRef());
            assertEquals("run", child.parentExecutionId());
            assertEquals("root-trace", child.traceId());
            assertEquals(0, r.f.count("arte_ai_reservation"));
            r.f.disable();
            assertThrows(RuntimeException.class, () -> r.selector.child("run", step.childExecutionId(), r.context.authorization().scopes(), r.context.deadline(), r.context).block(WAIT));
        }
    }

    @Test
    public void missingStepScopeExpansionAndResourceScopeRemovalAreRejected() throws Exception {
        try (var r = new Rig()) {
            assertThrows(RuntimeException.class, () -> r.selector.child("run", "client-child", r.context.authorization().scopes(), r.context.deadline(), r.context).block(WAIT));
            var scopes = new HashSet<>(r.context.authorization().scopes());
            scopes.add("admin");
            assertThrows(RuntimeException.class, () -> r.selector.child("run", r.step.childExecutionId(), scopes, r.context.deadline(), r.context).block(WAIT));
            var tool = r.tool();
            assertThrows(RuntimeException.class, () -> r.selector.child("run", tool.childExecutionId(), Set.of("ai:invoke"), r.context.deadline(), r.context).block(WAIT));
        }
    }
}
