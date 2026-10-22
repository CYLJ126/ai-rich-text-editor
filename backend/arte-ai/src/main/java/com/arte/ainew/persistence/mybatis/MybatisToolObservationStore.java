package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.persistence.mybatis.mapper.BudgetMapper;
import com.arte.ainew.persistence.mybatis.mapper.ExecutionMapper;
import com.arte.ainew.persistence.mybatis.mapper.ToolObservationMapper;
import com.arte.ainew.pojo.budget.BudgetReservation;
import com.arte.ainew.pojo.budget.BudgetSettlement;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.Attempt;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ToolObservationStore;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import javax.sql.DataSource;
import java.util.*;
import java.util.function.Supplier;

/**
 * 查询不扫描大工具快照；按索引取候选，再按至多 20 个主键批量读取同事务事实。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/22 14:40 ✾
 */
public final class MybatisToolObservationStore implements ToolObservationStore {

    private final ToolObservationMapper observations;
    private final ExecutionMapper executions;
    private final BudgetMapper budgets;
    private final ExecutionRecordCodec codec;
    private final Scheduler scheduler;
    private final TransactionTemplate transaction;

    public MybatisToolObservationStore(DataSource source, ExecutionRecordCodec codec, Scheduler scheduler) {
        var sessions = new SqlSessionTemplate(ExecutionSqlSessionFactory.create(source));
        observations = sessions.getMapper(ToolObservationMapper.class);
        executions = sessions.getMapper(ExecutionMapper.class);
        budgets = sessions.getMapper(BudgetMapper.class);
        this.codec = Objects.requireNonNull(codec);
        this.scheduler = Objects.requireNonNull(scheduler);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setReadOnly(true);
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        transaction.setTimeout(10);
    }

    private <T> Mono<T> read(Supplier<T> work) {
        return Mono.fromCallable(() -> transaction.execute(ignored -> work.get())).subscribeOn(scheduler);
    }

    private static String ownerKey(ExecutionOwner owner) {
        return CanonicalJson.key(owner.tenantId(), owner.workspaceId(), owner.subjectId());
    }

    @Override
    public Mono<Scan> scan(ExecutionOwner owner, ToolObservation.Query query, Position after, int limit) {
        ContractChecks.range(limit, "scan limit", 1, ToolObservation.STATISTICS_SCAN_LIMIT);
        return read(() -> {
            var rows = observations.scan(ownerKey(owner), query.from().toEpochMilli(), query.until().toEpochMilli(),
                    query.bindingId() == null ? null : CanonicalJson.key(query.bindingId()), after == null ? null : after.acceptedAt(),
                    after == null ? null : after.key(), limit + 1);
            var values = rows.stream().limit(limit).map(row -> {
                require(row.observation() != null && CanonicalJson.sha256(row.observation()).equals(row.digest()));
                ToolObservationMetadata metadata;
                try {
                    metadata = ToolObservationMetadata.decode(row.observation());
                } catch (RuntimeException invalid) {
                    throw new IllegalStateException("Tool observation projection is invalid", invalid);
                }
                require(CanonicalJson.key(metadata.invocationId()).equals(row.invocationKey()));
                return new Candidate(row.invocationKey(), row.acceptedAt(), metadata);
            }).toList();
            return new Scan(values, rows.size() > limit, !observations.legacy(ownerKey(owner)).isEmpty());
        });
    }

    @Override
    public Mono<List<Entry>> load(ExecutionOwner owner, List<Candidate> candidates) {
        ContractChecks.range(candidates.size(), "observation batch", 1, 20);
        return read(() -> {
            var keys = candidates.stream().map(Candidate::key).toList();
            var invocations = new HashMap<String, Invocation>();
            for (var value : executions.invocationSnapshots(ownerKey(owner), keys)) {
                var invocation = codec.decode(value, Invocation.class);
                require(ExecutionOwner.from(invocation.request().context()).equals(owner) && invocation.request().kind() == CapabilityDescriptor.Kind.TOOL);
                invocations.put(invocation.request().context().executionId(), invocation);
            }
            var attempts = new HashMap<String, List<Attempt>>();
            for (var value : observations.attempts(keys)) {
                var attempt = codec.decode(value, Attempt.class);
                require(invocations.containsKey(attempt.invocationId()));
                attempts.computeIfAbsent(attempt.invocationId(), ignored -> new ArrayList<>()).add(attempt);
            }
            var reservationKeys = attempts.values().stream().flatMap(Collection::stream).map(Attempt::budgetReservationId)
                    .filter(Objects::nonNull).distinct().map(CanonicalJson::key).toList();
            var reservations = new HashMap<String, BudgetReservation>();
            if (!reservationKeys.isEmpty()) for (var value : budgets.reservationSnapshots(reservationKeys)) {
                var reservation = codec.decode(value, BudgetReservation.class);
                require(invocations.containsKey(reservation.invocationId()));
                reservations.put(reservation.reservationId(), reservation);
            }
            var charges = new HashMap<String, ToolCharge>();
            var settlements = new HashMap<String, BudgetSettlement>();
            if (!reservationKeys.isEmpty()) {
                var rows = observations.settlements(reservationKeys);
                require(rows.size() <= 2000);
                for (var value : rows) {
                    var settlement = codec.decode(value, BudgetSettlement.class);
                    require(reservations.containsKey(settlement.reservationId()));
                    if (settlement.state() != BudgetSettlement.State.PENDING_RECONCILIATION) {
                        var prior = settlements.putIfAbsent(settlement.reservationId(), settlement);
                        require(prior == null || prior.state() == settlement.state() && prior.charge().equals(settlement.charge()));
                    }
                }
            }
            if (!reservationKeys.isEmpty()) for (var row : observations.bills(keys, ownerKey(owner))) {
                require(ownerKey(owner).equals(row.ownerKey()) && CanonicalJson.sha256(row.snapshot()).equals(row.digest()));
                var attempt = attempts.values().stream().flatMap(Collection::stream)
                        .filter(a -> CanonicalJson.key(a.attemptId()).equals(row.attemptKey()) && CanonicalJson.key(a.invocationId()).equals(row.invocationKey()))
                        .findFirst().orElseThrow(() -> new IllegalStateException("Tool bill attempt mismatch"));
                charges.put(attempt.attemptId(), codec.decode(row.snapshot(), ToolCharge.class));
            }
            var approvals = new HashMap<String, ToolApproval>();
            for (var value : observations.approvals(keys, ownerKey(owner))) {
                var approval = codec.decode(value, ToolApproval.class);
                require(owner.equals(approval.owner()) && invocations.containsKey(approval.invocationId()));
                approvals.put(approval.invocationId(), approval);
            }
            return candidates.stream().map(candidate -> {
                var metadata = candidate.metadata();
                var invocation = Objects.requireNonNull(invocations.get(metadata.invocationId()), "Tool invocation missing");
                var input = (ToolInvocation) invocation.request().input();
                require(input.tool().id().equals(metadata.toolId()) && input.tool().version().equals(metadata.toolVersion())
                        && invocation.request().binding().id().equals(metadata.bindingId()) && invocation.acceptedAt().toEpochMilli() == candidate.acceptedAt()
                        && ("CHARGE".equals(metadata.billingMode()) == (invocation.request().context().budgetRef() != null)));
                var actual = attempts.getOrDefault(metadata.invocationId(), List.of());
                require(actual.size() <= 10 && (invocation.activeAttemptId() == null || actual.stream().anyMatch(a -> a.attemptId().equals(invocation.activeAttemptId()))));
                var ownedReservations = new HashMap<String, BudgetReservation>();
                var ownedCharges = new HashMap<String, ToolCharge>();
                var ownedSettlements = new HashMap<String, BudgetSettlement>();
                for (var attempt : actual) {
                    if (attempt.budgetReservationId() != null) {
                        var reservation = Objects.requireNonNull(reservations.get(attempt.budgetReservationId()), "Tool reservation missing");
                        require(reservation.invocationId().equals(metadata.invocationId()) && reservation.attemptId().equals(attempt.attemptId()));
                        ownedReservations.put(reservation.reservationId(), reservation);
                        if (settlements.containsKey(reservation.reservationId()))
                            ownedSettlements.put(reservation.reservationId(), settlements.get(reservation.reservationId()));
                    }
                    if (charges.containsKey(attempt.attemptId()))
                        ownedCharges.put(attempt.attemptId(), charges.get(attempt.attemptId()));
                }
                return new Entry(candidate, invocation, List.copyOf(actual), Map.copyOf(ownedReservations), Map.copyOf(ownedCharges), Map.copyOf(ownedSettlements), approvals.get(metadata.invocationId()));
            }).toList();
        });
    }

    private static void require(boolean valid) {
        if (!valid) throw new IllegalStateException("Tool observation integrity mismatch");
    }
}
