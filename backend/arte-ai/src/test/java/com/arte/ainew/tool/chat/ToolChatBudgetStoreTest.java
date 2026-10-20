package com.arte.ainew.tool.chat;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.pojo.budget.BudgetCommands;
import com.arte.ainew.pojo.budget.BudgetReservation;
import com.arte.ainew.pojo.budget.BudgetSettlement;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.generation.ToolCall;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.spi.persistence.DispatchPreparationStore;
import org.junit.Test;
import reactor.core.publisher.Flux;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import static com.arte.ainew.pojo.execution.StoreOutcome.Code.*;
import static com.arte.ainew.pojo.tool.ToolChatRun.Phase.*;
import static com.arte.ainew.serialization.CanonicalJson.key;
import static com.arte.ainew.spi.persistence.DispatchPreparationStore.BillingMode.CHARGE;
import static com.arte.ainew.spi.persistence.DispatchPreparationStore.BillingMode.FREE;
import static org.junit.Assert.*;

/**
 * 两个真实存储实例、同库预算与故障回滚；没有实际模型或业务工具调用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 20:20 ✾
 */
public class ToolChatBudgetStoreTest {

    static final Duration WAIT = Duration.ofSeconds(15);

    static Money money(String value) {
        return new Money(new BigDecimal(value), Currency.getInstance("CNY"));
    }

    static final class Rig implements AutoCloseable {
        final ToolChatRunStoreTest.Rig r = new ToolChatRunStoreTest.Rig();
        final MybatisExecutionPersistence first = new MybatisExecutionPersistence(r.source, r.codec, r.io);
        final MybatisExecutionPersistence second = new MybatisExecutionPersistence(r.source, r.codec, r.io);
        final ToolChatRunSnapshot s;
        Invocation child;
        StoreOutcome.Code acceptanceCode;
        OutboxMessage claim;

        Rig() {
            this("1", "100");
        }

        Rig(String limit, String accountLimit) {
            this(limit, accountLimit, true);
        }

        Rig(String limit, String accountLimit, boolean budgeted) {
            var old = r.input("run", "budgeted");
            var price = new ToolChatBudgetPlan.ModelPrice(money("0"), money("5468.75")); // 128 output tokens => 0.7
            var choices = new ArrayList<ToolChatBudgetPlan.Selection>();
            choices.add(new ToolChatBudgetPlan.Selection(old.model().definition(), old.model().capability().definition(), CapabilityDescriptor.Kind.GENERATION,
                    CHARGE, "wallet", old.model().rate(), price));
            old.tools().entries().forEach(e -> choices.add(new ToolChatBudgetPlan.Selection(e.binding().definition(), e.binding().tool().tool().capability(),
                    CapabilityDescriptor.Kind.TOOL, FREE, null, null, null)));
            var plan = new ToolChatBudgetPlan(List.of(money(limit), new Money(new BigDecimal("2"), Currency.getInstance("USD"))), choices);
            s = budgeted ? new ToolChatRunSnapshot(old.context(), old.initialContext(), old.model(), old.generation(), old.tools(), old.limits(), old.receivedAt(), plan) : old;
            first.createAccount(new BudgetCommands.Account("wallet", r.owner, money(accountLimit), money("0"), money("0"), s.model().rate(), 0)).block(WAIT);
            assertTrue(r.store.accept(s, ignored -> r.authorize()).block(WAIT).successful());
            var step = r.advance(r.model(s, r.claim(), "first-model")).value().steps().getFirst();
            accept(step, "wallet");
        }

        void accept(ToolChatStep step, String wallet) {
            var c = s.context();
            var context = new ExecutionContext(step.childExecutionId(), c.traceId(), c.authorization(), c.deadline(), "run", wallet, c.releaseRef(), "submit-" + step.childExecutionId());
            CapabilityInput input;
            com.arte.ainew.common.reference.DefinitionRef binding, capability;
            if (step.input() instanceof ToolChatStep.Model model) {
                input = new GenerationRequest(model.context().messages(), s.generation(), s.tools().descriptions(), new GenerationRequest.TextOutput());
                binding = s.model().definition();
                capability = s.model().capability().definition();
            } else {
                var call = ((ToolChatStep.Tool) step.input()).call();
                input = new ToolInvocation(call.callId(), call.tool(), call.arguments());
                var b = s.tools().tool(call.tool()).binding();
                binding = b.definition();
                capability = b.tool().tool().capability();
            }
            var request = new InvocationRequest<>(capability, binding, input.kind(), input, new ExecutionOptions(c.deadline(), 3, 4096, input instanceof GenerationRequest ? 5 : 0, input instanceof GenerationRequest ? 1 : 0), context);
            var candidate = new Invocation(request, key("child-request", step.childExecutionId()), null,
                    input instanceof GenerationRequest ? s.initialContext().snapshotId() : null, null, Invocation.State.ACCEPTED, 0, null, null, null, s.receivedAt(), s.receivedAt());
            var accepted = first.accept(new ExecutionCommands.Accept(candidate, null)).block(WAIT);
            acceptanceCode = accepted.code();
            if (!accepted.successful()) {
                assertEquals(INVALID_STATE, accepted.code());
                child = null;
                return;
            }
            assertTrue(accepted.toString(), accepted.successful());
            child = accepted.value();
            claim = first.claim(OutboxMessage.Kind.DISPATCH, "worker", Duration.ofSeconds(45), 10).block(WAIT).stream()
                    .filter(m -> m.invocationId().equals(step.childExecutionId())).findFirst().orElseThrow();
        }

        DispatchPreparationStore.Command command() {
            return new DispatchPreparationStore.Command(new ExecutionCommands.Version(r.owner, child.request().context().executionId(), child.version()),
                    claim, "attempt-" + child.request().context().executionId(), Duration.ofSeconds(30),
                    new DispatchPreparationStore.Charge(money("0.7"), s.model().rate(), Duration.ofSeconds(1)));
        }

        DispatchPreparationStore.PreparedDispatch prepare() {
            return first.prepareDispatch(command()).block(WAIT).value();
        }

        ToolChatBudget budget() {
            return r.store.budgets(r.owner, "run").block(WAIT).getFirst();
        }

        BudgetCommands.Account account() {
            return first.account(r.owner, "wallet").block(WAIT);
        }

        StoreOutcome<BudgetReservation> settle(Attempt attempt, String command, String charge) {
            var reservation = first.reservation(r.owner, attempt.budgetReservationId()).block(WAIT);
            var state = charge == null ? BudgetSettlement.State.PENDING_RECONCILIATION : BudgetSettlement.State.SETTLED;
            var settlement = new BudgetSettlement(command, reservation.reservationId(), state, Usage.unknown(), charge == null ? null : money(charge), Instant.now());
            return first.settle(new BudgetCommands.Settle(r.owner, reservation.version(), settlement,
                    charge == null ? BudgetCommands.Evidence.UNKNOWN_COST : BudgetCommands.Evidence.PROVIDER_BILL,
                    charge == null ? null : "bill-" + command)).block(WAIT);
        }

        BudgetCommands.Settle bill(Attempt attempt, String command, String charge) {
            var reservation = first.reservation(r.owner, attempt.budgetReservationId()).block(WAIT);
            return new BudgetCommands.Settle(r.owner, reservation.version(), new BudgetSettlement(command, reservation.reservationId(), BudgetSettlement.State.SETTLED,
                    Usage.unknown(), money(charge), Instant.now()), BudgetCommands.Evidence.PROVIDER_BILL, "bill-" + command);
        }

        void nextModel() {
            r.wake();
            var lease = r.claim();
            var tool = new ToolChatStep.Plan(0, new ToolChatStep.Tool(new ToolCall("call", r.fixture.revision().tool().definition(), com.arte.ainew.tool.discovery.LocalToolFixtures.object())));
            assertTrue(r.advance(new ToolChatCommands.Advance(lease, 1, "tools", ToolChatRun.State.WAITING_TOOL, WAITING_TOOLS, 0, List.of(tool), null, null, null)).successful());
            r.wake();
            assertTrue(r.advance(new ToolChatCommands.Advance(r.claim(), 2, "continue", ToolChatRun.State.RUNNING, READY_MODEL, 1, List.of(), null, null, null)).successful());
            r.wake();
            var receipt = r.advance(new ToolChatCommands.Advance(r.claim(), 3, "second-model", ToolChatRun.State.RUNNING, WAITING_MODEL, 1,
                    List.of(new ToolChatStep.Plan(1, new ToolChatStep.Model(s.initialContext()))), null, null, null)).value();
            accept(receipt.steps().getFirst(), "wallet");
        }

        public void close() {
            r.close();
        }
    }

    @Test
    public void chargedDispatchOccupiesRunAndAccountInOneTransactionAndIsNotASecondCharge() {
        try (var f = new Rig()) {
            var result = f.prepare();
            assertTrue(result.ready());
            assertEquals(money("0.7"), f.budget().held());
            assertEquals(money("0.7"), f.account().held());
            assertEquals(money("0"), f.budget().charged());
            assertEquals(1, f.r.count("arte_ai_tool_chat_budget_allocation"));
            assertEquals(1, f.r.count("arte_ai_reservation"));
            assertEquals(BigDecimal.ZERO, f.r.store.budgets(f.r.owner, "run").block(WAIT).getLast().held().amount());
            assertEquals("USD", f.r.store.budgets(f.r.owner, "run").block(WAIT).getLast().limit().currency().getCurrencyCode());
        }
    }

    @Test
    public void concurrentDuplicateDispatchCannotReserveOrTransmitTwice() {
        try (var f = new Rig()) {
            var c = f.command();
            var results = Flux.merge(f.first.prepareDispatch(c), f.second.prepareDispatch(c)).collectList().block(WAIT);
            assertEquals(1, results.stream().filter(StoreOutcome::successful).count());
            assertEquals(1, f.r.count("arte_ai_tool_chat_budget_allocation"));
            assertEquals(money("0.7"), f.budget().held());
        }
    }

    @Test
    public void heldUnknownCostPreventsNextModelFromOversubscribingRunEvenWhenAccountHasMoney() {
        try (var f = new Rig()) {
            var first = f.prepare();
            f.settle(first.attempt(), "unknown", null);
            assertEquals(money("0.7"), f.budget().held());
            assertEquals(money("0"), f.budget().charged());
            f.nextModel();
            var next = f.prepare();
            assertFalse(next.ready());
            assertEquals(Attempt.Dispatch.NOT_STARTED, next.attempt().dispatch());
            assertNull(next.attempt().budgetReservationId());
            assertEquals(1, f.r.count("arte_ai_tool_chat_budget_allocation"));
            assertEquals(money("0.7"), f.account().held());
        }
    }

    @Test
    public void insufficientAccountLeavesNeitherRunHoldNorAllocation() {
        try (var f = new Rig("1", "0.5")) {
            assertFalse(f.prepare().ready());
            assertEquals(money("0"), f.budget().held());
            assertEquals(0, f.r.count("arte_ai_tool_chat_budget_allocation"));
            assertEquals(0, f.r.count("arte_ai_reservation"));
        }
    }

    @Test
    public void settlementMovesHoldToActualChargeAndSameCommandDoesNotSettleTwice() {
        try (var f = new Rig()) {
            var prepared = f.prepare();
            var bill = f.bill(prepared.attempt(), "bill", "0.4");
            assertEquals(APPLIED, f.first.settle(bill).block(WAIT).code());
            assertEquals(REPLAYED, f.second.settle(bill).block(WAIT).code());
            assertEquals(money("0"), f.budget().held());
            assertEquals(money("0.4"), f.budget().charged());
            assertEquals(money("0.4"), f.account().charged());
            assertEquals(1, f.r.count("arte_ai_settlement"));
            assertEquals(INVALID_STATE, f.settle(prepared.attempt(), "another-final", "0.4").code());
        }
    }

    @Test
    public void concurrentFinalSettlementsHaveExactlyOneEffect() {
        try (var f = new Rig()) {
            var p = f.prepare();
            var bill1 = f.bill(p.attempt(), "one", "0.4");
            var bill2 = f.bill(p.attempt(), "two", "0.4");
            var replies = Flux.merge(f.first.settle(bill1), f.second.settle(bill2)).collectList().block(WAIT);
            assertEquals(1, replies.stream().filter(StoreOutcome::successful).count());
            assertEquals(money("0.4"), f.budget().charged());
            assertEquals(money("0"), f.budget().held());
        }
    }

    @Test
    public void rootTerminalAndReservationExpiryDoNotReleaseUnknownFeesButLateBillCanClose() {
        try (var f = new Rig()) {
            var p = f.prepare();
            f.settle(p.attempt(), "unknown", null);
            f.r.wake();
            assertTrue(f.r.advance(new ToolChatCommands.Advance(f.r.claim(), 1, "cancel-root", ToolChatRun.State.CANCELLED, FINISHED, 0, List.of(), null, null, null)).successful());
            assertEquals(money("0.7"), f.budget().held());
            var old = f.first.reservation(f.r.owner, p.attempt().budgetReservationId()).block(WAIT);
            var expired = new BudgetReservation(old.reservationId(), old.budgetRef(), old.invocationId(), old.attemptId(), old.reserved(), old.rateVersion(), old.state(), old.version(),
                    old.reservedAt().minusSeconds(120), old.expiresAt().minusSeconds(120));
            f.r.jdbc.update("UPDATE arte_ai_reservation SET workspace_snapshot=? WHERE id_key=?", f.r.codec.encode(expired), key(old.reservationId()));
            assertTrue(expired.expiresAt().isBefore(Instant.now()));
            // 过期及根取消不是退款证据，原占用仍保留到下面的可信账单。
            assertEquals(APPLIED, f.settle(p.attempt(), "late-bill", "0.6").code());
            assertEquals(money("0.6"), f.budget().charged());
            assertEquals(money("0"), f.budget().held());
        }
    }

    @Test
    public void oversizedRealBillIsRetainedAndPermanentlyBlocksNewDispatch() {
        for (String limit : List.of("1", "3"))
            try (var f = new Rig(limit, "100")) {
                var actual = limit.equals("1") ? "1.2" : "0.8"; // Quote breach blocks even when the Run total is still below L.
                var p = f.prepare();
                assertEquals(APPLIED, f.settle(p.attempt(), "overspend", actual).code());
                assertEquals(money(actual), f.budget().charged());
                assertTrue(f.budget().blocked());
                assertEquals(money(actual), f.account().charged());
                f.nextModel();
                assertEquals(INVALID_STATE, f.acceptanceCode);
                assertNull(f.child); // 超额阻断已前移到新子调用受理边界。
                assertEquals(1, f.r.count("arte_ai_tool_chat_budget_allocation"));
            }
    }

    @Test
    public void dispatchFailureRollsBackAccountRunAllocationAttemptAndOutboxTogether() {
        try (var f = new Rig()) {
            f.r.jdbc.execute("ALTER TABLE arte_ai_attempt ADD CONSTRAINT fail_dispatch CHECK (workspace_snapshot NOT LIKE '%MAY_HAVE_EXECUTED%')");
            assertThrows(RuntimeException.class, f::prepare);
            assertEquals(money("0"), f.budget().held());
            assertEquals(money("0"), f.account().held());
            assertEquals(0, f.r.count("arte_ai_reservation"));
            assertEquals(0, f.r.count("arte_ai_attempt"));
            assertEquals(0, f.r.count("arte_ai_tool_chat_budget_allocation"));
            assertEquals(Invocation.State.ACCEPTED, f.first.find(f.r.owner, f.child.request().context().executionId()).block(WAIT).state());
        }
    }

    @Test
    public void settlementFailureRollsBackRunAndAccountAndCanBeRetried() {
        try (var f = new Rig()) {
            var p = f.prepare();
            var bill = f.bill(p.attempt(), "bill", "0.4");
            f.r.jdbc.execute("ALTER TABLE arte_ai_settlement ADD CONSTRAINT fail_settle CHECK (evidence_kind <> 'PROVIDER_BILL')");
            assertThrows(RuntimeException.class, () -> f.first.settle(bill).block(WAIT));
            assertEquals(money("0.7"), f.budget().held());
            assertEquals(money("0"), f.budget().charged());
            assertEquals(money("0.7"), f.account().held());
            f.r.jdbc.execute("ALTER TABLE arte_ai_settlement DROP CONSTRAINT fail_settle");
            assertTrue(f.second.settle(bill).block(WAIT).successful());
            assertEquals(money("0.4"), f.budget().charged());
        }
    }

    @Test
    public void retryAttemptHasItsOwnAllocationAndOnlyProvenNoExecutionCanReleasePriorHold() {
        try (var f = new Rig("3", "100")) {
            var p = f.prepare();
            var error = new ExecutionError("PROVIDER_REJECTED", ExecutionError.Phase.INVOCATION, true, ExecutionError.SideEffect.NONE, ExecutionError.Certainty.KNOWN, "trace");
            var failed = f.first.updateConditionally(new ExecutionCommands.FailAttempt(ExecutionCommands.Guard.from(f.r.owner, p.invocation(), p.attempt()),
                    Attempt.State.FAILED, error, Usage.unknown())).block(WAIT).value();
            var reservation = f.first.reservation(f.r.owner, failed.budgetReservationId()).block(WAIT);
            assertTrue(f.first.settle(new BudgetCommands.Settle(f.r.owner, reservation.version(), new BudgetSettlement("proven-reject", reservation.reservationId(),
                    BudgetSettlement.State.RELEASED, Usage.unknown(), money("0"), Instant.now()), BudgetCommands.Evidence.PROVEN_NO_EXECUTION, "rejection-evidence")).block(WAIT).successful());
            var current = f.first.find(f.r.owner, failed.invocationId()).block(WAIT);
            var retry = new DispatchPreparationStore.Command(new ExecutionCommands.Version(f.r.owner, failed.invocationId(), current.version()),
                    f.claim, "retry-attempt", Duration.ofSeconds(30), new DispatchPreparationStore.Charge(money("0.7"), f.s.model().rate(), Duration.ofSeconds(30)));
            var second = f.second.prepareDispatch(retry).block(WAIT).value();
            assertTrue(second.ready());
            assertEquals(2, second.attempt().attemptNumber());
            assertEquals(2, f.r.count("arte_ai_tool_chat_budget_allocation"));
            assertEquals(money("0.7"), f.budget().held());
            assertTrue(f.settle(second.attempt(), "retry-bill", "0.4").successful());
            assertEquals(money("0.4"), f.budget().charged());
        }
    }

    @Test
    public void freeToolDoesNotReserveEitherLedgerAndCurrencyLimitsCannotBeBorrowed() {
        try (var f = new Rig("0.5", "100")) {
            assertFalse(f.prepare().ready()); // USD 2 cannot cover a CNY 0.7 request.
            f.r.wake();
            var call = new ToolCall("free-call", f.r.fixture.revision().tool().definition(), com.arte.ainew.tool.discovery.LocalToolFixtures.object());
            var step = f.r.advance(new ToolChatCommands.Advance(f.r.claim(), 1, "free-tool", ToolChatRun.State.WAITING_TOOL, WAITING_TOOLS, 0,
                    List.of(new ToolChatStep.Plan(0, new ToolChatStep.Tool(call))), null, null, null)).value().steps().getFirst();
            f.accept(step, null);
            var command = new DispatchPreparationStore.Command(new ExecutionCommands.Version(f.r.owner, f.child.request().context().executionId(), 0),
                    f.claim, "free-attempt", Duration.ofSeconds(30), new DispatchPreparationStore.Free());
            var result = f.first.prepareDispatch(command).block(WAIT).value();
            assertTrue(result.ready());
            assertEquals(FREE, result.billingMode());
            assertEquals(0, f.r.count("arte_ai_tool_chat_budget_allocation"));
            assertEquals(0, f.r.count("arte_ai_reservation"));
            assertEquals(money("0"), f.budget().held());
            assertEquals(money("0"), f.account().held());
        }
    }

    @Test
    public void legacyRunCannotDispatchWithoutExplicitBudgetPlan() {
        try (var f = new Rig("1", "100", false)) {
            assertTrue(f.r.store.budgets(f.r.owner, "run").block(WAIT).isEmpty());
            assertNull(f.child); // 无预算快照在子受理边界即被拒绝。
            assertEquals(0, f.r.count("arte_ai_attempt"));
            assertEquals(0, f.r.count("arte_ai_reservation"));
        }
    }

    @Test
    public void standaloneReserveAndAttemptApisCannotBypassRunDispatchChecks() {
        try (var f = new Rig()) {
            assertEquals(INVALID_STATE, f.first.createAttempt(new ExecutionCommands.CreateAttempt(new ExecutionCommands.Version(f.r.owner,
                    f.child.request().context().executionId(), 0), "bypass", "worker", Duration.ofSeconds(30))).block(WAIT).code());
            var c = f.command();
            var wrongPrice = new DispatchPreparationStore.Command(c.invocation(), c.claim(), c.attemptId(), c.attemptLease(),
                    new DispatchPreparationStore.Charge(money("0"), f.s.model().rate(), Duration.ofSeconds(1)));
            assertEquals(INVALID_STATE, f.first.prepareDispatch(wrongPrice).block(WAIT).code());
            assertEquals(0, f.r.count("arte_ai_attempt"));
            assertEquals(money("0"), f.budget().held());
        }
    }
}
