package com.arte.ainew.tool.execution;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.BudgetReservation;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.budget.Reconciliation;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.spi.persistence.DispatchPreparationStore;
import org.junit.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Currency;
import java.util.List;

import static com.arte.ainew.tool.discovery.LocalToolFixtures.*;
import static org.junit.Assert.*;

/**
 * 真实 H2 目录、受理、Worker、账户及结算；账单来自受信测试适配器，不访问收费供应商。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/21 14:00 ✾
 */
public class ToolBillingIntegrationTest {

    static final Duration WAIT = Duration.ofSeconds(15);

    static Money money(String n) {
        return new Money(new BigDecimal(n), Currency.getInstance("CNY"));
    }

    static ToolPrice price(String n) {
        return new ToolPrice(ref("rate", "paid-tool-call", "v1"), money(n));
    }

    static ToolResult success(LocalToolCall c, ToolCharge charge) {
        return new ToolResult(c.call().request().input().callId(), ToolResult.Outcome.SUCCEEDED, object("text", text("result")), List.of(), List.of(), ExecutionError.SideEffect.NONE, null, charge);
    }

    static BudgetCommands.Account account(ToolExecutionFixture f) {
        return f.executions.account(f.owner, "tool-wallet").block(WAIT);
    }

    static BudgetReservation reservation(ToolExecutionFixture f, String id) {
        return f.executions.reservation(f.owner, f.attempt(id).budgetReservationId()).block(WAIT);
    }

    @Test
    public void paidCallReservesSettlesAndReplayCannotChargeTwice() throws Exception {
        try (var f = new ToolExecutionFixture(price("0.5"))) {
            f.operation = c -> reactor.core.publisher.Mono.just(success(c, new ToolCharge(money("0.5"), "invoice-one")));
            f.submit("one");
            f.service.submit(f.input(), f.context("duplicate", "one")).block(WAIT);
            assertTrue(f.codec.encode(f.store.snapshot(f.owner, "one").block(WAIT)).contains("\"schemaVersion\":6"));
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.SUCCEEDED, f.invocation("one").state());
            assertEquals(money("0.5"), account(f).charged());
            assertEquals(money("0"), account(f).held());
            assertEquals(BudgetReservation.State.SETTLED, reservation(f, "one").state());
            assertEquals(1, f.count("arte_ai_reservation"));
            assertEquals(1, f.count("arte_ai_tool_bill"));
            f.billing.settle(f.invocation("one")).block(WAIT);
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(1, f.invocations.get());
            assertEquals(money("0.5"), account(f).charged());
        }
    }

    @Test
    public void zeroUnitPriceRemainsChargeWithReservation() throws Exception {
        try (var f = new ToolExecutionFixture(price("0"))) {
            f.operation = c -> reactor.core.publisher.Mono.just(success(c, new ToolCharge(money("0"), "zero-bill")));
            f.submit("one");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertNotNull(f.attempt("one").budgetReservationId());
            assertEquals(1, f.count("arte_ai_reservation"));
            assertEquals(BudgetReservation.State.SETTLED, reservation(f, "one").state());
        }
    }

    @Test
    public void missingBillKeepsHoldDespiteSuccessfulBusinessResultAndManualBillDoesNotChangeOutcome() throws Exception {
        try (var f = new ToolExecutionFixture(price("0.7"))) {
            f.operation = c -> reactor.core.publisher.Mono.just(success(c, null));
            f.submit("one");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.SUCCEEDED, f.invocation("one").state());
            assertEquals(BudgetReservation.State.PENDING_RECONCILIATION, reservation(f, "one").state());
            assertEquals(money("0.7"), account(f).held());
            assertEquals(1L, f.executions.pendingReconciliations(f.owner, "tool-wallet", 1, 10).block(WAIT).total());
            var original = f.invocation("one");
            var r = reservation(f, "one");
            var command = new Reconciliation.Confirm(f.owner, original.request().context().authorization().principal(), "trace", "manual", "tool-wallet", "one", original.version(), r.reservationId(), r.version(), money("0.4"), "verified-invoice", "reviewed", true);
            assertTrue(f.executions.confirmReconciliation(command).block(WAIT).successful());
            assertTrue(f.executions.confirmReconciliation(command).block(WAIT).successful());
            assertEquals(Invocation.State.SUCCEEDED, f.invocation("one").state());
            assertEquals(money("0.4"), account(f).charged());
            assertEquals(money("0"), account(f).held());
        }
    }

    @Test
    public void insufficientBalanceNeverCallsAdapter() throws Exception {
        try (var f = new ToolExecutionFixture(price("11"))) {
            f.submit("one");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(0, f.invocations.get());
            assertEquals(0, f.count("arte_ai_reservation"));
            assertEquals(Invocation.State.FAILED, f.invocation("one").state());
        }
    }

    @Test
    public void unknownOrOmittedAccountCannotTurnPaidToolFree() throws Exception {
        try (var f = new ToolExecutionFixture(price("1"))) {
            assertThrows(RuntimeException.class, () -> f.service.submit(new ToolAdmissionInput(f.binding.bindingId(), object(), null, null, null), f.context("one", "one")).block(WAIT));
            assertThrows(RuntimeException.class, () -> f.service.submit(new ToolAdmissionInput(f.binding.bindingId(), object(), null, null, null, "other-wallet"), f.context("two", "two")).block(WAIT));
            assertEquals(0, f.count("arte_ai_invocation"));
            assertEquals(0, f.invocations.get());
        }
    }

    @Test
    public void actualOverrunIsRecordedAndTariffBlocksFurtherDispatch() throws Exception {
        try (var f = new ToolExecutionFixture(price("0.5"))) {
            f.operation = c -> reactor.core.publisher.Mono.just(success(c, new ToolCharge(money("0.8"), "overrun")));
            f.submit("one");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(money("0.8"), account(f).charged());
            assertEquals(1, (int) f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_price_guard WHERE blocked=true", Integer.class));
            assertThrows(RuntimeException.class, () -> f.submit("two"));
            assertEquals(1, f.invocations.get());
            assertEquals(1, f.count("arte_ai_attempt"));
        }
    }

    @Test
    public void forgedLowerQuoteIsRejectedAtomically() throws Exception {
        try (var f = new ToolExecutionFixture(price("1"))) {
            f.submit("one");
            var claim = f.claim("worker");
            var i = f.invocation("one");
            var outcome = f.executions.prepareDispatch(new DispatchPreparationStore.Command(new ExecutionCommands.Version(f.owner, "one", i.version()), claim, "attempt", Duration.ofSeconds(10), money("0"), price("1").rate(), Duration.ofHours(1))).block(WAIT);
            assertFalse(outcome.successful());
            assertEquals(0, f.count("arte_ai_attempt"));
            assertEquals(0, f.count("arte_ai_reservation"));
        }
    }

    @Test
    public void feeReceiptSurvivesTinyResultEnvelopeLimit() throws Exception {
        try (var f = new ToolExecutionFixture(price("0.5"))) {
            f.operation = c -> reactor.core.publisher.Mono.just(success(c, new ToolCharge(money("0.5"), "small-envelope-bill")));
            f.service.submit(new ToolAdmissionInput(f.binding.bindingId(), object(), null, null, 1L, "tool-wallet"), f.context("one", "one")).block(WAIT);
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(money("0.5"), account(f).charged());
            assertEquals(1, f.count("arte_ai_tool_bill"));
        }
    }

    @Test
    public void paidRunChargesSeparateAccountAndSharesCurrencyLimit() throws Exception {
        try (var r = new ToolChatLoopTest.Rig(false, com.arte.ainew.pojo.control.CapabilityDescriptor.SideEffect.READ_ONLY, true, null, price("0.5"))) {
            r.f.operation = c -> reactor.core.publisher.Mono.just(success(c, new ToolCharge(money("0.5"), "run-tool-invoice")));
            r.submit();
            r.tick();
            r.restart();
            r.until(run -> run.state().terminal());
            assertEquals(ToolChatRun.State.SUCCEEDED, r.run().state());
            assertEquals(2, r.modelCalls.get());
            assertEquals(money("0.5"), account(r.f).charged());
            assertEquals(3, r.count("arte_ai_reservation"));
            assertEquals(money("0.50024"), r.runs.budgets(r.f.owner, "run").block(WAIT).getFirst().charged());
            assertEquals(1, (int) r.f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_chat_budget_allocation WHERE charge_kind='TOOL'", Integer.class));
            assertEquals("RUN", r.service.submit(r.input(), r.session, r.context).block(WAIT).kind().name());
        }
    }

    @Test
    public void runBudgetRejectsToolBeforeAdapterAndWithoutPartialHold() throws Exception {
        try (var r = new ToolChatLoopTest.Rig(false, com.arte.ainew.pojo.control.CapabilityDescriptor.SideEffect.READ_ONLY, true, null, price("2"))) {
            r.submit();
            r.until(run -> run.state().terminal());
            assertEquals(0, r.f.invocations.get());
            assertEquals(money("0"), account(r.f).held());
            assertEquals(money("0"), account(r.f).charged());
            assertEquals(1, r.count("arte_ai_reservation"));
        }
    }

    @Test
    public void paidTimeoutKeepsUnknownCostHeld() throws Exception {
        try (var f = new ToolExecutionFixture(price("0.5"))) {
            f.operation = c -> reactor.core.publisher.Mono.never();
            f.service.submit(new ToolAdmissionInput(f.binding.bindingId(), object(), Duration.ofSeconds(1), null, null, "tool-wallet"), f.context("one", "one")).block(WAIT);
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(Invocation.State.TIMED_OUT, f.invocation("one").state());
            assertEquals(BudgetReservation.State.PENDING_RECONCILIATION, reservation(f, "one").state());
            assertEquals(money("0.5"), account(f).held());
        }
    }

    @Test
    public void billConfirmationCannotResolveUnknownToolBusinessOutcome() throws Exception {
        try (var f = new ToolExecutionFixture(price("0.5"))) {
            f.operation = c -> reactor.core.publisher.Mono.just(new ToolResult(c.call().request().input().callId(), ToolResult.Outcome.UNKNOWN, null, List.of(), List.of(), ExecutionError.SideEffect.POSSIBLE,
                    new ExecutionError("TOOL_UNKNOWN", ExecutionError.Phase.INVOCATION, false, ExecutionError.SideEffect.POSSIBLE, ExecutionError.Certainty.UNKNOWN, "trace")));
            f.submit("one");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            var before = f.invocation("one");
            var reserve = reservation(f, "one");
            assertTrue(f.executions.confirmReconciliation(new Reconciliation.Confirm(f.owner, before.request().context().authorization().principal(), "trace", "manual", "tool-wallet", "one", before.version(), reserve.reservationId(), reserve.version(), money("0.4"), "invoice", "verified ended", true)).block(WAIT).successful());
            assertEquals(Invocation.State.UNKNOWN, f.invocation("one").state());
            assertEquals(before.error(), f.invocation("one").error());
            assertEquals(money("0.4"), account(f).charged());
        }
    }

    @Test
    public void disablingNewBillingDoesNotBlockStoredBillSettlement() throws Exception {
        try (var f = new ToolExecutionFixture(price("0.5"))) {
            f.operation = c -> reactor.core.publisher.Mono.just(success(c, new ToolCharge(money("0.5"), "before-disable")));
            f.submit("one");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            f.billing.enabled(false);
            f.billing.settle(f.invocation("one")).block(WAIT);
            assertEquals(money("0.5"), account(f).charged());
            assertThrows(RuntimeException.class, () -> f.submit("two"));
        }
    }

    @Test
    public void differentCurrenciesRemainSeparateInRunAndAccountLedgers() throws Exception {
        var usd = new ToolPrice(price("1").rate(), new Money(new BigDecimal("0.5"), Currency.getInstance("USD")));
        try (var r = new ToolChatLoopTest.Rig(false, com.arte.ainew.pojo.control.CapabilityDescriptor.SideEffect.READ_ONLY, true, null, usd)) {
            var input = r.input();
            var both = new ToolChatSubmission(input.conversationId(), input.expectedVersion(), input.text(), input.binding(), input.capability(), input.modelBudgetRef(), input.maxInputTokens(), input.generation(), input.toolBindingIds(), input.limits(),
                    List.of(money("1"), new Money(new BigDecimal("1"), Currency.getInstance("USD"))), input.toolBudgetRefs());
            r.f.operation = c -> reactor.core.publisher.Mono.just(success(c, new ToolCharge(usd.perCall(), "usd-invoice")));
            r.service.submit(both, r.session, r.context).block(WAIT);
            r.until(run -> run.state().terminal());
            assertEquals(ToolChatRun.State.SUCCEEDED, r.run().state());
            var balances = r.runs.budgets(r.f.owner, "run").block(WAIT);
            assertEquals(2, balances.size());
            assertEquals(money("0.00024"), balances.stream().filter(v -> v.limit().currency().getCurrencyCode().equals("CNY")).findFirst().orElseThrow().charged());
            assertEquals(usd.perCall(), balances.stream().filter(v -> v.limit().currency().getCurrencyCode().equals("USD")).findFirst().orElseThrow().charged());
        }
    }

    @Test
    public void gatewayProvesNoInvocationAndReleasesReservation() throws Exception {
        try (var f = new ToolExecutionFixture(price("0.5"))) {
            f.submit("one");
            f.executionAllowed.set(false);
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(0, f.invocations.get());
            assertEquals(Invocation.State.FAILED, f.invocation("one").state());
            assertEquals(BudgetReservation.State.RELEASED, reservation(f, "one").state());
            assertEquals(money("0"), account(f).held());
            assertEquals(ToolCharge.Kind.NO_INVOCATION, f.executions.toolCharge(f.owner, "one", f.attempt("one").attemptId()).block(WAIT).kind());
        }
    }

    @Test
    public void simultaneousChargesCannotOverdrawAccount() throws Exception {
        try (var f = new ToolExecutionFixture(price("7"))) {
            f.operation = c -> reactor.core.publisher.Mono.just(success(c, new ToolCharge(money("7"), "concurrent-bill")));
            f.submit("one");
            f.submit("two");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(1, f.invocations.get());
            assertEquals(1, f.count("arte_ai_reservation"));
            assertEquals(money("7"), account(f).charged());
            assertEquals(money("0"), account(f).held());
        }
    }

    @Test
    public void simultaneousRunChargesCannotOverdrawSharedRunLimit() throws Exception {
        try (var r = new ToolChatLoopTest.Rig(false, com.arte.ainew.pojo.control.CapabilityDescriptor.SideEffect.READ_ONLY, true, null, price("0.7"))) {
            r.proposedCalls = 2;
            r.f.operation = c -> reactor.core.publisher.Mono.just(success(c, new ToolCharge(money("0.7"), "bill-" + c.call().request().input().callId())));
            var input = r.input();
            var concurrent = new ToolChatSubmission(input.conversationId(), input.expectedVersion(), input.text(), input.binding(), input.capability(), input.modelBudgetRef(), input.maxInputTokens(), input.generation(), input.toolBindingIds(),
                    new ToolChatRunSnapshot.Limits(input.limits().timeout(), 3, 4, 2), input.budgetLimits(), input.toolBudgetRefs());
            r.service.submit(concurrent, r.session, r.context).block(WAIT);
            r.until(run -> run.state().terminal());
            assertEquals(1, r.f.invocations.get());
            assertEquals(money("0.7"), account(r.f).charged());
            assertEquals(2, r.count("arte_ai_reservation"));
        }
    }

    @Test
    public void paidSnapshotsRejectVersionDowngradeAndModelWalletSubstitution() throws Exception {
        try (var r = new ToolChatLoopTest.Rig(false, com.arte.ainew.pojo.control.CapabilityDescriptor.SideEffect.READ_ONLY, true, null, price("0.5"))) {
            r.submit();
            String run = r.f.codec.encode(r.runs.snapshot(r.f.owner, "run").block(WAIT));
            assertTrue(run.contains("\"schemaVersion\":3"));
            assertThrows(IllegalArgumentException.class, () -> r.f.codec.decode(run.replace("\"schemaVersion\":3", "\"schemaVersion\":2"), ToolChatRunSnapshot.class));
            String tool = r.f.codec.encode(r.f.v1.revision());
            assertTrue(tool.contains("\"schemaVersion\":2"));
            assertThrows(IllegalArgumentException.class, () -> r.f.codec.decode(tool.replace("\"schemaVersion\":2", "\"schemaVersion\":1"), ToolDefinitionRevision.class));
            var input = r.input();
            var wrong = new ToolChatSubmission(input.conversationId(), input.expectedVersion(), input.text(), input.binding(), input.capability(), input.modelBudgetRef(), input.maxInputTokens(), input.generation(), input.toolBindingIds(), input.limits(), input.budgetLimits(), java.util.Map.of(r.f.binding.bindingId(), "wallet"));
            assertThrows(RuntimeException.class, () -> r.service.submit(wrong, r.session, new ExecutionContext("other", "trace", r.context.authorization(), r.context.deadline(), null, null, "release", "other")).block(WAIT));
        }
    }

    @Test
    public void chargedUnknownEntersBusinessReviewWithoutClearingCostHold() throws Exception {
        try (var f = new ToolExecutionFixture(price("0.5"))) {
            var reviews = new com.arte.ainew.persistence.mybatis.MybatisToolOutcomeStore(f.source, f.codec, f.executions,
                    new com.arte.ainew.application.tool.outcome.ToolOutcomeQueryRegistry(List.of()), com.arte.ainew.config.NewAiSchedulingProperties.disabled(), f.io);
            f.executions.configureToolOutcomeRecorder(reviews::recordUnknownInCurrentTransaction);
            f.operation = c -> reactor.core.publisher.Mono.just(new ToolResult(c.call().request().input().callId(), ToolResult.Outcome.UNKNOWN, null, List.of(), List.of(), ExecutionError.SideEffect.POSSIBLE,
                    new ExecutionError("TOOL_UNKNOWN", ExecutionError.Phase.INVOCATION, false, ExecutionError.SideEffect.POSSIBLE, ExecutionError.Certainty.UNKNOWN, "trace")));
            f.submit("one");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(1, f.count("arte_ai_tool_outcome_check"));
            assertEquals(money("0.5"), account(f).held());
            assertEquals(com.arte.ainew.pojo.tool.ToolOutcomeCheck.State.NEEDS_REVIEW, reviews.find(f.owner, "one").block(WAIT).state());
        }
    }

    @Test
    public void billingMigrationAppliesToExistingToolSchema() throws Exception {
        try (var f = new ToolExecutionFixture()) {
            f.jdbc.execute("DROP TABLE arte_ai_tool_bill");
            f.jdbc.execute("DROP TABLE arte_ai_tool_price_guard");
            new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(new org.springframework.core.io.ClassPathResource("arte-ai-tool-billing-migration.sql")).populate(f.ddl);
            assertEquals(0, f.count("arte_ai_tool_bill"));
            assertEquals(0, f.count("arte_ai_tool_price_guard"));
        }
    }

    @Test
    public void modelAccountIsRejectedBeforeRunAcceptance() throws Exception {
        try (var r = new ToolChatLoopTest.Rig(false, com.arte.ainew.pojo.control.CapabilityDescriptor.SideEffect.READ_ONLY, true, null, price("0.5"))) {
            var input = r.input();
            var wrong = new ToolChatSubmission(input.conversationId(), input.expectedVersion(), input.text(), input.binding(), input.capability(), input.modelBudgetRef(), input.maxInputTokens(), input.generation(), input.toolBindingIds(), input.limits(), input.budgetLimits(), java.util.Map.of(r.f.binding.bindingId(), "wallet"));
            assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> r.service.submit(wrong, r.session, r.context).block(WAIT));
            assertEquals(0, r.count("arte_ai_tool_chat_run"));
            assertEquals(0, r.count("arte_ai_invocation"));
        }
    }

    @Test
    public void recoverySettlesOriginalReceiptWithoutRepeatingTool() throws Exception {
        try (var f = new ToolExecutionFixture(price("0.5"))) {
            f.operation = c -> reactor.core.publisher.Mono.just(success(c, new ToolCharge(money("0.5"), "durable-bill")));
            var failing = new com.arte.ainew.spi.persistence.ExecutionResultStore() {
                public reactor.core.publisher.Mono<StoreOutcome<ResultRef>> put(ExecutionOwner owner, String invocation, String attempt, String key, InvocationResult result) {
                    return reactor.core.publisher.Mono.error(new IllegalStateException("result persistence fault"));
                }

                public reactor.core.publisher.Mono<InvocationResult> find(ExecutionOwner owner, String invocation, ResultRef reference) {
                    return f.payloads.find(owner, invocation, reference);
                }

                public reactor.core.publisher.Mono<StagedResult> staged(ExecutionOwner owner, String invocation, String attempt, String key) {
                    return f.payloads.staged(owner, invocation, attempt, key);
                }
            };
            f.submit("one");
            var message = f.claim("worker");
            assertThrows(RuntimeException.class, () -> f.dispatcher(f.executions, failing, f.gateway).dispatch(message, f.runtime("one")).block(WAIT));
            assertEquals(1, f.count("arte_ai_tool_bill"));
            f.expireAttempt("one");
            f.dispatcher.dispatch(message, f.runtime("one")).block(WAIT);
            assertEquals(1, f.invocations.get());
            assertEquals(money("0.5"), account(f).charged());
            assertEquals(BudgetReservation.State.SETTLED, reservation(f, "one").state());
        }
    }

    @Test
    public void invoiceFactsCannotBeOverwritten() throws Exception {
        try (var f = new ToolExecutionFixture(price("0.5"))) {
            var bill = new ToolCharge(money("0.5"), "invoice");
            f.operation = c -> reactor.core.publisher.Mono.just(success(c, bill));
            f.submit("one");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            String attempt = f.attempt("one").attemptId();
            f.executions.recordToolCharge(f.owner, "one", attempt, bill).block(WAIT);
            assertThrows(RuntimeException.class, () -> f.executions.recordToolCharge(f.owner, "one", attempt, new ToolCharge(money("0.2"), "other-invoice")).block(WAIT));
            assertEquals(bill, f.executions.toolCharge(f.owner, "one", attempt).block(WAIT));
            assertEquals(money("0.5"), account(f).charged());
        }
    }

    @Test
    public void wrongCallReceiptCannotChargeThisAttempt() throws Exception {
        try (var f = new ToolExecutionFixture(price("0.5"))) {
            f.operation = c -> reactor.core.publisher.Mono.just(new ToolResult("other-call", ToolResult.Outcome.SUCCEEDED,
                    object("text", text("result")), List.of(), List.of(), ExecutionError.SideEffect.NONE, null,
                    new ToolCharge(money("0.5"), "wrong-call-bill")));
            f.submit("one");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(0, f.count("arte_ai_tool_bill"));
            assertEquals(BudgetReservation.State.PENDING_RECONCILIATION, reservation(f, "one").state());
            assertEquals(money("0.5"), account(f).held());
            assertEquals(money("0"), account(f).charged());
        }
    }

    @Test
    public void adapterCannotForgeGatewayNonInvocationProof() throws Exception {
        try (var f = new ToolExecutionFixture(price("0.5"))) {
            f.operation = c -> reactor.core.publisher.Mono.just(success(c,
                    new ToolCharge(money("0"), "forged-proof", ToolCharge.Kind.NO_INVOCATION)));
            f.submit("one");
            f.worker(f.dispatcher).pollOnce().block(WAIT);
            assertEquals(0, f.count("arte_ai_tool_bill"));
            assertEquals(BudgetReservation.State.PENDING_RECONCILIATION, reservation(f, "one").state());
            assertEquals(money("0.5"), account(f).held());
        }
    }
}
