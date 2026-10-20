package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.persistence.mybatis.mapper.ExecutionMapper;
import com.arte.ainew.persistence.mybatis.mapper.ToolAdmissionMapper;
import com.arte.ainew.persistence.mybatis.mapper.ToolApprovalMapper;
import com.arte.ainew.pojo.execution.Attempt;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ToolApprovalStore;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

import static com.arte.ainew.pojo.execution.StoreOutcome.Code.*;
import static com.arte.ainew.serialization.CanonicalJson.key;

/**
 * 审批决定的物理事务：Invocation → approval 行锁，复核当前资格与固定内容，再提交唯一决定及排队／终态。
 * 不发外部请求，不代替宿主成员／审批授权，不使用 JVM 锁或缓存决定。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 00:05 ✾
 */
@Slf4j
public final class MybatisToolApprovalStore implements ToolApprovalStore {
    private final ToolApprovalMapper approvals;
    private final ToolAdmissionMapper snapshots;
    private final ExecutionMapper execution;
    private final MybatisExecutionPersistence executions;
    private final MybatisToolBindingStore bindings;
    private final ExecutionRecordCodec codec;
    private final Scheduler scheduler;
    private final TransactionTemplate transaction;
    private final DataSource source;

    public MybatisToolApprovalStore(DataSource source, ExecutionRecordCodec codec, Scheduler scheduler,
                                    MybatisExecutionPersistence executions, MybatisToolBindingStore bindings) {
        ContractChecks.require(executions.uses(source) && bindings.uses(source), "Approval dependencies must use same physical DataSource");
        var sessions = new SqlSessionTemplate(ExecutionSqlSessionFactory.create(source));
        approvals = sessions.getMapper(ToolApprovalMapper.class);
        snapshots = sessions.getMapper(ToolAdmissionMapper.class);
        execution = sessions.getMapper(ExecutionMapper.class);
        this.executions = executions;
        this.source = source;
        this.bindings = bindings;
        this.codec = Objects.requireNonNull(codec);
        this.scheduler = Objects.requireNonNull(scheduler);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(30);
    }

    private <T> Mono<T> io(Supplier<T> work) {
        return Mono.defer(() -> Mono.fromCallable(() -> {
            if (Schedulers.isInNonBlockingThread())
                throw new IllegalStateException("Approval requires bounded JDBC worker");
            return transaction.execute(status -> work.get());
        }).subscribeOn(scheduler).cache());
    }

    @Override
    public Mono<ToolApproval> find(ExecutionOwner owner, String invocationId) {
        Objects.requireNonNull(owner);
        ContractChecks.id(invocationId, "invocationId");
        return io(() -> {
            var found = ToolApprovalRecords.read(approvals.find(key(invocationId), false), codec);
            return found != null && found.owner().equals(owner) ? found : null;
        });
    }

    @Override
    public Mono<ToolApproval> visible(ExecutionOwner actor, String approvalId, String invocationId) {
        Objects.requireNonNull(actor);
        ContractChecks.require((approvalId == null) != (invocationId == null), "Exactly one approval locator required");
        ContractChecks.optionalId(approvalId, "approvalId");
        ContractChecks.optionalId(invocationId, "invocationId");
        return io(() -> ToolApprovalRecords.read(approvals.visible(key(actor.tenantId(), actor.workspaceId(), actor.subjectId()),
                approvalId == null ? null : key(approvalId), invocationId == null ? null : key(invocationId)), codec));
    }

    @Override
    public Mono<Inbox> inbox(ExecutionOwner actor, String cursor, int limit) {
        ContractChecks.range(limit, "approval inbox limit", 1, 50);
        if (cursor != null) ContractChecks.digest(cursor, "approval cursor");
        return io(() -> {
            var rows = approvals.inbox(key(actor.tenantId(), actor.workspaceId(), actor.subjectId()), cursor, limit + 1);
            boolean exhausted = rows.size() <= limit;
            var shown = rows.stream().limit(limit).toList();
            return new Inbox(shown.stream().map(row -> ToolApprovalRecords.read(row, codec)).toList(),
                    exhausted || shown.isEmpty() ? null : shown.getLast().invocationKey(), exhausted);
        });
    }

    @Override
    public Mono<Void> requireApproved(GatewayCall<ToolInvocation> call) {
        return io(() -> {
            requireApprovedInCurrentTransaction(call);
            return true;
        }).then();
    }

    @Override
    public void requireApprovedInCurrentTransaction(GatewayCall<ToolInvocation> call) {
        requireWritableTransaction();
        var invocation = invocation(call.request().context().executionId());
        require(invocation != null && invocation.request().equals(call.request())
                && invocation.state() == Invocation.State.RUNNING && call.attempt().attemptId().equals(invocation.activeAttemptId()), INVALID_STATE);
        var approval = ToolApprovalRecords.read(approvals.find(key(call.request().context().executionId()), true), codec);
        require(approval != null && approval.state() == ToolApproval.State.APPROVED, INVALID_STATE);
        var fixed = snapshot(approval, invocation);
        require(fixed.binding().resolvedBinding().equals(call.binding()), INVALID_STATE);
        var attempt = codec.decode(Objects.requireNonNull(execution.attemptSnapshot(key(call.attempt().attemptId()), true)), Attempt.class);
        require(attempt.attemptId().equals(call.attempt().attemptId()) && attempt.invocationId().equals(invocation.request().context().executionId())
                && Objects.equals(attempt.remoteRequestId(), call.attempt().remoteRequestId())
                && attempt.fencingToken() == call.attempt().fencingToken() && attempt.workerId().equals(call.attempt().workerId())
                && attempt.state() == Attempt.State.RUNNING && attempt.dispatch() == Attempt.Dispatch.MAY_HAVE_EXECUTED
                && attempt.leaseExpiresAt().isAfter(executions.databaseTimeInCurrentTransaction()), LEASE_LOST);
        require(invocation.request().options().deadline().isAfter(executions.databaseTimeInCurrentTransaction())
                && !executions.cancellationRequestedInCurrentTransaction(invocation), INVALID_STATE);
    }

    private void requireWritableTransaction() {
        if (!org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive()
                || !org.springframework.transaction.support.TransactionSynchronizationManager.hasResource(source)
                || org.springframework.transaction.support.TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Domain write requires the same physical writable datasource transaction");
    }

    @Override
    public void requireCurrentBindingInCurrentTransaction(GatewayCall<ToolInvocation> call) {
        requireWritableTransaction();
        var fixed = bindings.fixedInCurrentTransaction(ExecutionOwner.from(call.request().context()), call.request().binding());
        require(fixed.successful() && fixed.value().resolvedBinding().equals(call.binding()), INVALID_STATE);
    }

    @Override
    public Mono<StoreOutcome<ToolApprovalReceipt>> decide(ToolApprovalCommand command, ExecutionOwner actor,
                                                          BiConsumer<ToolApproval, ToolExecutionSnapshot> authorization) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(actor);
        Objects.requireNonNull(authorization);
        return io(() -> {
            var invocation = invocation(command.invocationId());
            require(invocation != null && ExecutionOwner.from(invocation.request().context()).equals(command.owner()), NOT_FOUND);
            var approval = ToolApprovalRecords.read(approvals.find(key(command.invocationId()), true), codec);
            require(approval != null && approval.owner().equals(command.owner()) && approval.approvalId().equals(command.approvalId()), NOT_FOUND);
            requireActor(approval, actor);
            var snapshot = snapshot(approval, invocation);
            // 同键重放亦重新授权，不把历史批准当作当前身份资格。
            authorization.accept(approval, snapshot);
            String operation = key("tool-approval-decision", command.commandKey());
            String digest = CanonicalJson.digest(CanonicalJson.fields("command", command, "actor", actor));
            var previous = execution.operation(key(command.invocationId()), operation);
            if (!previous.isEmpty()) {
                require(previous.getFirst().digest().equals(digest), IDEMPOTENCY_CONFLICT);
                return StoreOutcome.replayed(codec.decode(previous.getFirst().resultSnapshot(), ToolApprovalReceipt.class));
            }
            require(command.subjectDigest().equals(approval.subjectDigest()), IDEMPOTENCY_CONFLICT);
            require(command.expectedVersion() == approval.version(), VERSION_CONFLICT);
            require(approval.state() == ToolApproval.State.PENDING && invocation.state() == Invocation.State.WAITING_APPROVAL
                    && invocation.activeAttemptId() == null, INVALID_STATE);
            var now = executions.databaseTimeInCurrentTransaction();
            require(now.isBefore(approval.plan().expiresAt())
                    && !now.plus(approval.plan().executionMargin()).isAfter(invocation.request().options().deadline()), INVALID_STATE);
            boolean approve = command.decision() == ToolApprovalCommand.Decision.APPROVE;
            if (approve) {
                var fixed = bindings.fixedInCurrentTransaction(approval.owner(), snapshot.binding().definition());
                require(fixed.successful() && fixed.value().equals(snapshot.binding()), INVALID_STATE);
            }
            var decided = approval.decided(approve ? ToolApproval.State.APPROVED : ToolApproval.State.REJECTED, actor.subjectId(), now);
            save(decided, approval.version());
            var result = approve ? executions.queueApprovedToolInCurrentTransaction(invocation,
                    executions.toolDispatchPlan(snapshot.binding().tool().tool().providerId(), snapshot.binding().tool().tool().defaultPolicy().timeout()))
                    : executions.finishWaitingToolInCurrentTransaction(invocation, Invocation.State.FAILED, "TOOL_APPROVAL_REJECTED");
            // 锁等待、治理及排队都可能消耗窗口；过期则整份决定／队列／事件回滚。
            require(executions.databaseTimeInCurrentTransaction().isBefore(approval.plan().expiresAt()), INVALID_STATE);
            var receipt = new ToolApprovalReceipt(decided, result);
            require(execution.insertCompletion(key(command.invocationId()), operation, digest, 0, 0, codec.encode(receipt)) == 1, INVALID_STATE);
            return StoreOutcome.applied(receipt);
        }).onErrorResume(MybatisExecutionPersistence.Rejected.class, error -> Mono.just(StoreOutcome.rejected(error.code)));
    }

    private static void requireActor(ToolApproval approval, ExecutionOwner actor) {
        if (!approval.owner().tenantId().equals(actor.tenantId()) || !approval.owner().workspaceId().equals(actor.workspaceId()))
            throw new AccessDeniedException("Approval scope denied");
        boolean allowed = approval.plan().mode() == ToolExecutionPolicy.ApprovalMode.REQUESTER_CONFIRMATION
                ? approval.owner().subjectId().equals(actor.subjectId())
                : !approval.owner().subjectId().equals(actor.subjectId()) && approval.plan().approverSubjectIds().contains(actor.subjectId());
        if (!allowed) throw new AccessDeniedException("Approval actor denied");
    }

    /**
     * 执行快照是不可变材料；每次决定重算审批摘要，不以审批 ID 或脱敏展示代替实际参数校验。
     */
    private ToolExecutionSnapshot snapshot(ToolApproval approval, Invocation invocation) {
        return ToolApprovalRecords.snapshot(approval, invocation, snapshots, codec);
    }

    @Override
    public Mono<Integer> expire(int limit) {
        ContractChecks.range(limit, "approval expiry batch", 1, 256);
        return io(() -> approvals.expired(executions.databaseTimeInCurrentTransaction().toEpochMilli(), limit))
                .flatMapMany(Flux::fromIterable).concatMap(id -> io(() -> {
                    // 索引扫描不先持审批锁，避免与决定／取消出现相反锁序。
                    var invocation = codec.decode(Objects.requireNonNull(execution.invocationSnapshot(id, true)), Invocation.class);
                    var approval = ToolApprovalRecords.read(approvals.find(id, true), codec);
                    if (approval == null || approval.state() != ToolApproval.State.PENDING) return 0;
                    if (executions.databaseTimeInCurrentTransaction().isBefore(approval.plan().expiresAt())) return 0;
                    require(invocation.state() == Invocation.State.WAITING_APPROVAL && invocation.activeAttemptId() == null, INVALID_STATE);
                    snapshot(approval, invocation);
                    executions.finishWaitingToolInCurrentTransaction(invocation, Invocation.State.TIMED_OUT, "TOOL_APPROVAL_EXPIRED");
                    return 1;
                }).onErrorResume(error -> {
                    // 损坏／不一致记录保留为待处理并告警；不让单条坏记录阻塞同批其他到期调用。
                    log.warn("AI tool approval expiry failed, invocationKey={}, type={}", id, error.getClass().getName());
                    return Mono.just(0);
                })).reduce(0, Integer::sum);
    }

    private Invocation invocation(String id) {
        String json = execution.invocationSnapshot(key(id), true);
        return json == null ? null : codec.decode(json, Invocation.class);
    }

    private void save(ToolApproval approval, long expected) {
        require(approvals.save(ToolApprovalRecords.row(approval, codec), expected) == 1, VERSION_CONFLICT);
        approvals.closeAudience(key(approval.invocationId()));
    }

    private static void require(boolean condition, StoreOutcome.Code code) {
        if (!condition) throw new MybatisExecutionPersistence.Rejected(code);
    }
}
