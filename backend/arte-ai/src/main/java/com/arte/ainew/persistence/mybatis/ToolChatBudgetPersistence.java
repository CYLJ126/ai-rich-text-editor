package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.persistence.mybatis.mapper.ToolChatBudgetMapper;
import com.arte.ainew.persistence.mybatis.mapper.ToolChatRunMapper;
import com.arte.ainew.pojo.budget.BudgetReservation;
import com.arte.ainew.pojo.budget.BudgetSettlement;
import com.arte.ainew.pojo.budget.Money;
import com.arte.ainew.pojo.execution.Attempt;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolChatRunDigests;
import com.arte.ainew.spi.persistence.DispatchPreparationStore;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import org.mybatis.spring.SqlSessionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

import static com.arte.ainew.pojo.execution.StoreOutcome.Code.*;
import static com.arte.ainew.serialization.CanonicalJson.key;

/**
 * 只在调用方已开启的同库事务中使用；无异步提交，不访问模型或外部计费系统。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 20:20 ✾
 */
final class ToolChatBudgetPersistence {

    private final ToolChatBudgetMapper mapper;
    private final ToolChatRunMapper runs;
    private final ExecutionRecordCodec codec;

    ToolChatBudgetPersistence(SqlSessionTemplate sessions, ExecutionRecordCodec codec) {
        this.mapper = sessions.getMapper(ToolChatBudgetMapper.class);
        this.runs = sessions.getMapper(ToolChatRunMapper.class);
        this.codec = codec;
    }

    private static void require(boolean condition, StoreOutcome.Code code) {
        if (!condition) throw new MybatisExecutionPersistence.Rejected(code);
    }

    private <T> T decode(String bytes, String digest, Class<T> type) {
        if (!CanonicalJson.sha256(bytes).equals(digest))
            throw new IllegalStateException("Run budget checksum mismatch");
        return codec.decode(bytes, type);
    }

    private ToolChatBudgetMapper.Balance row(ToolChatBudget value) {
        String bytes = codec.encode(value);
        return new ToolChatBudgetMapper.Balance(key(value.runId()), value.limit().currency().getCurrencyCode(), value.version(), CanonicalJson.sha256(bytes), bytes);
    }

    private ToolChatBudgetMapper.Allocation row(ToolChatBudgetAllocation a) {
        String bytes = codec.encode(a);
        return new ToolChatBudgetMapper.Allocation(key(a.runId()), key(a.invocationId()), key(a.attemptId()), a.attemptNumber(),
                a.chargeKind(), key(a.reservationId()), CanonicalJson.sha256(bytes), bytes);
    }

    void initialize(ToolChatRunSnapshot snapshot) {
        if (snapshot.budgetPlan() == null) return;
        for (var limit : snapshot.budgetPlan().limits()) {
            var zero = new Money(BigDecimal.ZERO, limit.currency());
            mapper.insertBalance(row(new ToolChatBudget(snapshot.context().executionId(), limit, zero, zero, false, 0)));
        }
    }

    List<ToolChatBudget> balances(String runId, boolean lock) {
        return mapper.balances(key(runId), lock).stream().map(r -> {
            var b = decode(r.snapshot(), r.digest(), ToolChatBudget.class);
            if (!b.runId().equals(runId) || !r.runKey().equals(key(runId)) || r.version() != b.version()
                    || !r.currency().equals(b.limit().currency().getCurrencyCode()))
                throw new IllegalStateException("Run budget index mismatch");
            return b;
        }).toList();
    }

    record Guard(ToolChatRunSnapshot snapshot, List<ToolChatBudget> budgets) {
    }

    /**
     * 新子调用入库后、Outbox 提交前锁根复核；沿用子→根锁序，停止门闩之后不再受理。
     */
    void admit(Invocation child, Instant time) {
        if (child.request().context().parentExecutionId() == null) return;
        var row = runs.find(key(child.request().context().parentExecutionId()), true);
        require(row != null, NOT_FOUND);
        var fixed = decode(row.inputSnapshot(), row.inputDigest(), ToolChatRunSnapshot.class);
        require(fixed.budgetPlan() != null, INVALID_STATE);
        var selection = fixed.budgetPlan().selection(child.request().binding());
        DispatchPreparationStore.BillingPlan billing = new DispatchPreparationStore.Free();
        if (selection.mode() == DispatchPreparationStore.BillingMode.CHARGE) {
            var stored = runs.step(key(child.request().context().executionId()));
            require(stored != null, INVALID_STATE);
            var step = decode(stored.snapshot(), stored.payloadDigest(), ToolChatStep.class);
            var amount = selection.kind() == com.arte.ainew.pojo.control.CapabilityDescriptor.Kind.TOOL ? selection.toolPrice().perCall()
                    : selection.price().quote(((ToolChatStep.Model) step.input()).context().budget().maxInputTokens(), fixed.generation().maxOutputTokens());
            billing = new DispatchPreparationStore.Charge(amount, selection.rate(), java.time.Duration.ofHours(1));
        }
        guard(child, billing, time);
    }

    /**
     * 子快照不能绕过 Run 选择时进一步收紧的审批、次数或容量策略。
     */
    void admitTool(ToolExecutionSnapshot child) {
        String parent = child.request().context().parentExecutionId();
        if (parent == null) return;
        var row = runs.find(key(parent), true);
        require(row != null, NOT_FOUND);
        var fixed = decode(row.inputSnapshot(), row.inputDigest(), ToolChatRunSnapshot.class);
        var entry = fixed.tools().tool(child.request().input().tool());
        require(entry.binding().equals(child.binding())
                && com.arte.ainew.application.tool.binding.ToolBindingConstraints.intersect(entry.policy(), child.policy()).equals(child.policy())
                && child.schemas().contains(entry.inputSchema()), INVALID_STATE);
    }

    /**
     * Invocation／Outbox 已锁定后，锁原 Run 资格，再按币种取得预算行；无反向子 Invocation 锁。
     */
    Guard guard(Invocation invocation, DispatchPreparationStore.BillingPlan billing, Instant time) {
        var child = invocation.request().context();
        if (child.parentExecutionId() == null) return null;
        var row = runs.find(key(child.parentExecutionId()), true);
        require(row != null, NOT_FOUND);
        var root = decode(row.stateSnapshot(), row.stateDigest(), ToolChatRun.class);
        var snapshot = decode(row.inputSnapshot(), row.inputDigest(), ToolChatRunSnapshot.class);
        require(row.runKey().equals(key(child.parentExecutionId())) && row.ownerKey().equals(key(root.owner().tenantId(), root.owner().workspaceId(), root.owner().subjectId()))
                && row.submissionDigest().equals(ToolChatRunDigests.submission(snapshot))
                && root.runId().equals(child.parentExecutionId()) && root.version() == row.version()
                && snapshot.context().executionId().equals(root.runId()) && root.owner().equals(ExecutionOwners.from(child))
                && root.owner().equals(ExecutionOwners.from(snapshot.context())), OWNER_MISMATCH);
        require(snapshot.budgetPlan() != null && row.deadline() == snapshot.context().deadline().toEpochMilli(), INVALID_STATE);
        require(!root.state().terminal() && root.state() != ToolChatRun.State.PENDING_RECONCILIATION
                && runs.receipt(row.runKey(), ToolChatCommands.CANCEL_MARKER) == null && time.toEpochMilli() < row.deadline()
                && child.traceId().equals(snapshot.context().traceId()) && Objects.equals(child.releaseRef(), snapshot.context().releaseRef())
                && !child.deadline().isAfter(snapshot.context().deadline())
                && snapshot.context().authorization().scopes().containsAll(child.authorization().scopes())
                && root.checkpoint().children().contains(child.executionId()), INVALID_STATE);
        var stored = runs.step(key(child.executionId()));
        require(stored != null && stored.runKey().equals(row.runKey()) && stored.childKey().equals(key(child.executionId())), INVALID_STATE);
        var step = decode(stored.snapshot(), stored.payloadDigest(), ToolChatStep.class);
        require(step.runId().equals(root.runId()) && step.childExecutionId().equals(child.executionId())
                && step.roundIndex() == root.checkpoint().roundIndex(), INVALID_STATE);
        ToolChatBudgetPlan.Selection selection;
        try {
            selection = snapshot.budgetPlan().selection(invocation.request().binding());
        } catch (IllegalArgumentException missing) {
            throw new MybatisExecutionPersistence.Rejected(INVALID_STATE);
        }
        require(selection.capability().equals(invocation.request().capability()) && selection.kind() == invocation.request().kind()
                && selection.mode() == billing.mode() && Objects.equals(selection.budgetRef(), child.budgetRef()), INVALID_STATE);
        if (billing instanceof DispatchPreparationStore.Charge charge && selection.kind() == com.arte.ainew.pojo.control.CapabilityDescriptor.Kind.GENERATION) {
            require(step.input() instanceof ToolChatStep.Model && root.checkpoint().phase() == ToolChatRun.Phase.WAITING_MODEL, INVALID_STATE);
            var context = ((ToolChatStep.Model) step.input()).context();
            require(invocation.request().input() instanceof GenerationRequest, INVALID_STATE);
            var generation = (GenerationRequest) invocation.request().input();
            require(Objects.equals(invocation.contextSnapshotId(), context.snapshotId()) && generation.messages().equals(context.messages())
                    && generation.options().equals(snapshot.generation()) && generation.tools().equals(snapshot.tools().descriptions()), INVALID_STATE);
            require(charge.rate().equals(selection.rate()), RATE_MISMATCH);
            require(charge.amount().equals(selection.price().quote(context.budget().maxInputTokens(), generation.options().maxOutputTokens())), INVALID_STATE);
        } else {
            require(step.input() instanceof ToolChatStep.Tool && (root.checkpoint().phase() == ToolChatRun.Phase.WAITING_TOOLS
                    || root.checkpoint().phase() == ToolChatRun.Phase.WAITING_APPROVAL)
                    && invocation.request().input() instanceof ToolInvocation, INVALID_STATE);
            var call = ((ToolChatStep.Tool) step.input()).call();
            var actual = (ToolInvocation) invocation.request().input();
            if (billing instanceof DispatchPreparationStore.Charge charge)
                require(selection.toolPrice() != null && selection.rate().equals(charge.rate()) && selection.toolPrice().perCall().equals(charge.amount()), INVALID_STATE);
            require(call.callId().equals(actual.callId()) && call.tool().equals(actual.tool()) && call.arguments().equals(actual.arguments()), INVALID_STATE);
        }
        var budgets = balances(root.runId(), true);
        require(budgets.stream().map(ToolChatBudget::limit).toList().equals(snapshot.budgetPlan().limits()), INVALID_STATE);
        require(budgets.stream().noneMatch(ToolChatBudget::blocked), INVALID_STATE);
        return new Guard(snapshot, budgets);
    }

    void capacity(Guard guard, Money quote) {
        if (guard == null) return;
        var b = guard.budgets().stream().filter(x -> x.limit().currency().equals(quote.currency())).findFirst().orElse(null);
        require(b != null, CURRENCY_MISMATCH);
        require(b.available().compareTo(quote.amount()) >= 0, INSUFFICIENT_BUDGET);
    }

    private void save(ToolChatBudget next, long expected) {
        require(mapper.saveBalance(row(next), expected) == 1, VERSION_CONFLICT);
    }

    void hold(Guard guard, Invocation invocation, Attempt attempt, BudgetReservation reservation) {
        if (guard == null) return;
        var b = guard.budgets().stream().filter(x -> x.limit().currency().equals(reservation.reserved().currency())).findFirst().orElseThrow();
        capacity(guard, reservation.reserved());
        mapper.insertAllocation(row(new ToolChatBudgetAllocation(b.runId(), reservation.invocationId(), attempt.attemptId(), attempt.attemptNumber(),
                invocation.request().kind() == com.arte.ainew.pojo.control.CapabilityDescriptor.Kind.TOOL ? "TOOL" : "MODEL", reservation.budgetRef(), reservation.rateVersion(), reservation.reservationId(), reservation.reserved(), BudgetReservation.State.RESERVED, null, 0)));
        save(new ToolChatBudget(b.runId(), b.limit(), b.charged(), new Money(b.held().amount().add(reservation.reserved().amount()), b.held().currency()),
                b.blocked(), b.version() + 1), b.version());
    }

    /**
     * 允许终态及原期限之后凭可信账单收尾；这里只锁 Run 预算，不取得新派发资格。
     */
    List<ToolChatBudget> settlementLocks(Invocation invocation, BudgetReservation reservation) {
        var parent = invocation.request().context().parentExecutionId();
        if (parent == null) return null;
        var budgets = balances(parent, true);
        require(!budgets.isEmpty(), INVALID_STATE);
        return budgets;
    }

    void settle(List<ToolChatBudget> budgets, Invocation invocation, BudgetReservation reservation, BudgetSettlement settlement) {
        if (budgets == null) return;
        var row = mapper.allocation(key(reservation.reservationId()), true);
        require(row != null, INVALID_STATE);
        var a = decode(row.snapshot(), row.digest(), ToolChatBudgetAllocation.class);
        require(a.runId().equals(invocation.request().context().parentExecutionId()) && row.runKey().equals(key(a.runId()))
                && a.invocationId().equals(reservation.invocationId()) && a.attemptId().equals(reservation.attemptId())
                && row.invocationKey().equals(key(a.invocationId())) && row.attemptKey().equals(key(a.attemptId()))
                && row.attemptNumber() == a.attemptNumber() && row.chargeKind().equals(a.chargeKind())
                && row.reservationKey().equals(key(a.reservationId())) && a.reservationId().equals(reservation.reservationId())
                && a.quote().equals(reservation.reserved()) && a.budgetRef().equals(reservation.budgetRef()) && a.rate().equals(reservation.rateVersion())
                && a.state() == reservation.state() && a.version() == reservation.version(), INVALID_STATE);
        var state = BudgetReservation.State.valueOf(settlement.state().name());
        boolean finished = state == BudgetReservation.State.SETTLED || state == BudgetReservation.State.RELEASED;
        var b = budgets.stream().filter(x -> x.limit().currency().equals(a.quote().currency())).findFirst().orElseThrow();
        if (finished) {
            require(b.held().amount().compareTo(a.quote().amount()) >= 0, INVALID_STATE);
            var held = new Money(b.held().amount().subtract(a.quote().amount()), b.limit().currency());
            var charged = new Money(b.charged().amount().add(settlement.charge().amount()), b.limit().currency());
            boolean blocked = b.blocked() || settlement.charge().amount().compareTo(a.quote().amount()) > 0
                    || charged.amount().add(held.amount()).compareTo(b.limit().amount()) > 0;
            save(new ToolChatBudget(b.runId(), b.limit(), charged, held, blocked, b.version() + 1), b.version());
        }
        require(mapper.saveAllocation(row(new ToolChatBudgetAllocation(a.runId(), a.invocationId(), a.attemptId(), a.attemptNumber(), a.chargeKind(),
                a.budgetRef(), a.rate(), a.reservationId(), a.quote(), state, finished ? settlement.charge() : null, a.version() + 1))) == 1, INVALID_STATE);
    }
}
