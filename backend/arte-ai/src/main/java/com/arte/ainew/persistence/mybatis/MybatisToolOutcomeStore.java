package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.application.tool.outcome.ToolOutcomeQueryRegistry;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.config.NewAiSchedulingProperties;
import com.arte.ainew.persistence.mybatis.mapper.*;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ToolOutcomeStore;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import static com.arte.ainew.pojo.tool.ToolOutcomeCheck.*;
import static com.arte.ainew.serialization.CanonicalJson.key;

/**
 * UNKNOWN 与核对工作项一致提交；调用→工作项→Attempt→调度锁，查询不持长期数据库锁。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 10:48 ✾
 */
@Slf4j
public final class MybatisToolOutcomeStore implements ToolOutcomeStore {

    private final DataSource source;
    private final ExecutionRecordCodec codec;
    private final MybatisExecutionPersistence executions;
    private final ToolOutcomeQueryRegistry registry;
    private final NewAiSchedulingProperties scheduling;
    private final Scheduler io;
    private final TransactionTemplate tx;
    private final ToolOutcomeMapper jobs;
    private final ExecutionMapper execution;
    private final ToolAdmissionMapper snapshots;
    private final ToolApprovalMapper approvals;
    private final SystemMapper system;
    private final SchedulingMapper capacity;
    private final PayloadMapper payload;
    private volatile boolean scanCompleted;

    public MybatisToolOutcomeStore(DataSource source, ExecutionRecordCodec codec, MybatisExecutionPersistence executions,
                                   ToolOutcomeQueryRegistry registry, NewAiSchedulingProperties scheduling, Scheduler io) {
        if (!executions.uses(source)) throw new IllegalArgumentException("Outcome must share physical datasource");
        this.source = source;
        this.codec = codec;
        this.executions = executions;
        this.registry = registry;
        this.scheduling = scheduling;
        this.io = io;
        var session = new SqlSessionTemplate(ExecutionSqlSessionFactory.create(source));
        jobs = session.getMapper(ToolOutcomeMapper.class);
        execution = session.getMapper(ExecutionMapper.class);
        snapshots = session.getMapper(ToolAdmissionMapper.class);
        approvals = session.getMapper(ToolApprovalMapper.class);
        system = session.getMapper(SystemMapper.class);
        capacity = session.getMapper(SchedulingMapper.class);
        payload = session.getMapper(PayloadMapper.class);
        tx = new TransactionTemplate(new DataSourceTransactionManager(source));
        tx.setTimeout(10);
    }

    private <T> Mono<T> io(Supplier<T> work) {
        return Mono.defer(() -> Mono.fromCallable(() -> {
            if (Schedulers.isInNonBlockingThread())
                throw new IllegalStateException("Outcome JDBC requires bounded worker");
            return tx.execute(status -> work.get());
        }).subscribeOn(io));
    }

    private void transaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || !TransactionSynchronizationManager.hasResource(source)
                || TransactionSynchronizationManager.isCurrentTransactionReadOnly())
            throw new IllegalStateException("Outcome requires same physical write transaction");
    }

    private long now() {
        return executions.databaseTimeInCurrentTransaction().toEpochMilli();
    }

    private static void check(boolean valid) {
        if (!valid) throw new IllegalStateException("Tool outcome identity, state or lease mismatch");
    }

    private Invocation invocation(String hash) {
        var bytes = execution.invocationSnapshot(hash, true);
        return bytes == null ? null : codec.decode(bytes, Invocation.class);
    }

    private String owner(Invocation inv) {
        var o = ExecutionOwner.from(inv.request().context());
        return key(o.tenantId(), o.workspaceId(), o.subjectId());
    }

    private ToolExecutionSnapshot snapshot(Invocation inv) {
        var row = snapshots.toolSnapshot(key(inv.request().context().executionId()), owner(inv));
        check(row != null && CanonicalJson.sha256(row.snapshot()).equals(row.payloadDigest()));
        var fixed = codec.decode(row.snapshot(), ToolExecutionSnapshot.class);
        check(fixed.request().equals(inv.request()) && fixed.requestDigest().equals(inv.requestDigest()) && row.requestDigest().equals(fixed.requestDigest())
                && row.submissionDigest().equals(fixed.submissionDigest()));
        if (fixed.policy().approvalMode() != ToolExecutionPolicy.ApprovalMode.NONE) {
            var approval = ToolApprovalRecords.read(approvals.find(key(inv.request().context().executionId()), false), codec);
            check(approval != null && approval.state() == ToolApproval.State.APPROVED);
            check(ToolApprovalRecords.snapshot(approval, inv, snapshots, codec).equals(fixed));
        }
        return fixed;
    }

    /**
     * 同 UNKNOWN 原写事务调用，不重置已存在任务、额度或有效窗口。
     */
    public void recordUnknownInCurrentTransaction(Invocation inv) {
        transaction();
        check(inv.state() == Invocation.State.UNKNOWN && inv.request().kind() == com.arte.ainew.pojo.control.CapabilityDescriptor.Kind.TOOL);
        if (inv.request().context().budgetRef() != null || inv.request().context().parentExecutionId() != null || inv.activeAttemptId() == null)
            return;
        String hash = key(inv.request().context().executionId());
        if (jobs.find(hash, true) != null) return;
        var fixed = snapshot(inv);
        var adapter = registry.find(fixed);
        var o = ExecutionOwner.from(inv.request().context());
        var original = codec.decode(Objects.requireNonNull(execution.attemptSnapshot(key(inv.activeAttemptId()), true)), Attempt.class);
        check(original.invocationId().equals(inv.request().context().executionId()) && original.state() == Attempt.State.UNKNOWN);
        long at = original.updatedAt().toEpochMilli();
        var job = new Case(hash, inv.request().context().executionId(), owner(inv), inv.activeAttemptId(), adapter.map(a -> a.operationKey(fixed)).orElse(""),
                key(o.tenantId(), o.workspaceId()), key(fixed.binding().tool().tool().providerId()), adapter.isPresent() ? State.CHECKING : State.NEEDS_REVIEW,
                0, 0, "", 0, 0, 0, Math.addExact(at, DELAYS.getFirst().toMillis()), at, Math.addExact(at, WINDOW.toMillis()), adapter.isPresent() ? "WAITING" : "OUTCOME_QUERY_UNSUPPORTED", "");
        check(jobs.insert(job) == 1);
    }

    /**
     * 首次启用有界回填；游标与工作项同事务，重启不重置窗口，后续 UNKNOWN 由原子登记覆盖。
     */
    @Override
    public Mono<Integer> recoverUnknownBatch() {
        if (scanCompleted) return Mono.just(0);
        return io(() -> {
            jobs.lockScan();
            var cursor = jobs.scanCursor();
            if (cursor.completed()) {
                scanCompleted = true;
                return 0;
            }
            var rows = jobs.scan(cursor.afterKey());
            int recovered = 0;
            for (var row : rows) {
                var inv = invocation(row.idKey());
                if (inv != null && inv.state() == Invocation.State.UNKNOWN && inv.request().kind() == com.arte.ainew.pojo.control.CapabilityDescriptor.Kind.TOOL) {
                    check(key(inv.request().context().executionId()).equals(row.idKey()));
                    if (jobs.find(row.idKey(), true) == null) {
                        recordUnknownInCurrentTransaction(inv);
                        recovered++;
                    }
                }
            }
            check(jobs.advanceScan(rows.isEmpty() ? cursor.afterKey() : rows.getLast().idKey(), rows.size() < 32) == 1);
            return recovered;
        });
    }

    @Override
    public Mono<Case> find(ExecutionOwner o, String id) {
        ContractChecks.id(id, "invocationId");
        return io(() -> {
            var found = jobs.find(key(id), false);
            return found != null && found.invocationId().equals(id) && found.ownerKey().equals(key(o.tenantId(), o.workspaceId(), o.subjectId())) ? found : null;
        });
    }

    @Override
    public Mono<List<Claim>> claim(String worker, int limit) {
        ContractChecks.id(worker, "workerId");
        ContractChecks.range(limit, "outcome claims", 1, 64);
        return io(() -> jobs.due(now(), 256)).flatMapMany(Flux::fromIterable)
                .concatMap(candidate -> io(() -> claimOne(candidate, worker)).onErrorResume(error -> {
                    log.warn("AI_TOOL_OUTCOME claim failed invocationKey={} type={}", candidate.invocationKey(), error.getClass().getSimpleName());
                    return Mono.empty();
                }))
                .take(Math.min(limit, scheduling.slots().outcomeCheck())).collectList();
    }

    private Claim claimOne(Case candidate, String worker) {
        var inv = invocation(candidate.invocationKey());
        var old = jobs.find(candidate.invocationKey(), true);
        if (inv == null || old == null || old.state() != State.CHECKING) return null;
        long at = now();
        if (old.leaseUntil() > at || old.nextCheck() > at) return null;
        if (inv.state() != Invocation.State.UNKNOWN) {
            save(old, old.changed(State.RESOLVED, old.checks(), "", old.fence(), 0, 0, old.nextCheck(), "ALREADY_CONFIRMED", old.evidenceKey()));
            return null;
        }
        check(old.invocationId().equals(inv.request().context().executionId()) && old.ownerKey().equals(owner(inv)) && old.attemptId().equals(inv.activeAttemptId()));
        check(old.checks() >= 0 && old.checks() <= DELAYS.size() && old.validUntil() == Math.addExact(old.createdAt(), WINDOW.toMillis()));
        boolean abandoned = !old.workerId().isEmpty();
        if (at >= old.validUntil() || !abandoned && old.checks() >= DELAYS.size()) {
            review(old, "OUTCOME_LIMIT_REACHED");
            return null;
        }
        var fixed = snapshot(inv);
        var adapter = registry.find(fixed);
        var originalOwner = ExecutionOwner.from(inv.request().context());
        check(old.spaceKey().equals(key(originalOwner.tenantId(), originalOwner.workspaceId())) && old.providerKey().equals(key(fixed.binding().tool().tool().providerId())));
        if (adapter.isEmpty() || !adapter.get().operationKey(fixed).equals(old.operationKey())) {
            review(old, "OUTCOME_QUERY_UNSUPPORTED");
            return null;
        }
        // 与普通派发共用容量锁和许可。这里只查询调度索引，不反向取得其他 Invocation 锁。
        if (!permit(old, old.fence() + 1, at)) return null;
        var attempt = executions.claimToolOutcomeInCurrentTransaction(inv, worker, LEASE);
        var claimed = old.changed(State.CHECKING, abandoned ? old.checks() : old.checks() + 1, worker, Math.addExact(old.fence(), 1), at + LEASE.toMillis(),
                abandoned ? old.queryDeadline() : Math.min(at + QUERY_TIMEOUT.toMillis(), old.validUntil()), old.nextCheck(), "CHECKING", old.evidenceKey());
        save(old, claimed);
        return new Claim(claimed, inv, attempt, fixed);
    }

    private boolean permit(Case job, long fence, long at) {
        if (!scheduling.enabled() || scheduling.toolManifest() == null)
            throw new IllegalStateException("Classified outcome capacity required");
        system.ensureLock(key("dispatch-scheduling", "v1"));
        var pool = capacity.pool();
        check(pool != null && pool.policyDigest().equals(key(scheduling.toString())) && pool.manifestDigest().equals(scheduling.toolManifest()));
        var permits = capacity.permits(at);
        if (permits.stream().filter(p -> p.workClass().equals("OUTCOME_CHECK")).count() >= scheduling.cluster(OutboxMessage.WorkClass.OUTCOME_CHECK)
                || permits.stream().filter(p -> p.spaceKey().equals(job.spaceKey())).count() >= scheduling.workspaceConcurrency()
                || permits.stream().filter(p -> p.providerKey().equals(job.providerKey())).count() >= scheduling.providerConcurrency())
            return false;
        String id = permitKey(job);
        var previous = capacity.findPermit(id);
        if (previous != null) capacity.release(id, previous.token());
        check(capacity.permit(id, fence, "OUTCOME_CHECK", job.spaceKey(), job.providerKey(), at + LEASE.toMillis()) == 1);
        return true;
    }

    private static String permitKey(Case job) {
        return key("tool-outcome-permit", job.invocationId());
    }

    private void save(Case old, Case changed) {
        check(jobs.save(changed, old.version()) == 1);
    }

    private void review(Case old, String code) {
        save(old, old.changed(State.NEEDS_REVIEW, old.checks(), "", old.fence(), 0, 0, old.nextCheck(), code, old.evidenceKey()));
    }

    private Case current(Claim claim, boolean query) {
        transaction();
        var inv = invocation(claim.work().invocationKey());
        var job = jobs.find(claim.work().invocationKey(), true);
        long at = now();
        check(job != null && job.equals(claim.work()) && job.state() == State.CHECKING && job.leaseUntil() > at && at < job.validUntil());
        check(inv != null && inv.equals(claim.invocation()) && inv.state() == Invocation.State.UNKNOWN && owner(inv).equals(job.ownerKey()));
        var attempt = codec.decode(Objects.requireNonNull(execution.attemptSnapshot(key(job.attemptId()), true)), Attempt.class);
        check(attempt.equals(claim.attempt()) && attempt.leaseExpiresAt().toEpochMilli() > at && execution.attemptPurpose(key(job.attemptId())).equals("RECONCILE"));
        check(snapshot(inv).equals(claim.snapshot()));
        if (query) check(job.queryDeadline() > at);
        var permit = capacity.findPermit(permitKey(job));
        check(permit != null && permit.token() == job.fence() && permit.workClass().equals("OUTCOME_CHECK") && permit.leaseUntil() > at);
        return job;
    }

    @Override
    public void requireCurrentQueryInCurrentTransaction(Claim claim) {
        current(claim, true);
    }

    @Override
    public Mono<ToolOutcomeEvidence> query(Claim claim, Supplier<ToolOutcomeEvidence> read) {
        return io(() -> {
            requireCurrentQueryInCurrentTransaction(claim);
            var evidence = Objects.requireNonNull(read.get());
            requireCurrentQueryInCurrentTransaction(claim);
            return evidence;
        });
    }

    @Override
    public Mono<Boolean> finish(Claim claim, ToolOutcomeEvidence evidence, String reason, boolean needsReview) {
        ContractChecks.text(reason, "outcome reason", 96);
        return io(() -> {
            var job = current(claim, false);
            long at = now();
            check(evidence.owner().equals(claim.owner()) && evidence.invocationId().equals(job.invocationId()) && evidence.attemptId().equals(job.attemptId()) && evidence.operationKey().equals(job.operationKey()));
            if (needsReview) {
                review(job, reason);
                release(job);
                return true;
            }
            if (evidence.decision() == ToolOutcomeEvidence.Decision.STILL_UNKNOWN || at >= job.queryDeadline()) {
                if (job.checks() >= DELAYS.size() || at >= job.validUntil()) review(job, "OUTCOME_LIMIT_REACHED");
                else
                    save(job, job.changed(State.CHECKING, job.checks(), "", job.fence(), 0, 0, Math.min(at + DELAYS.get(job.checks()).toMillis(), job.validUntil()), reason, ""));
                release(job);
                return true;
            }
            var result = evidence.result();
            validateResult(claim, evidence);
            String encoded = CanonicalJson.normalize(codec.encode(new InvocationResult.Tool(result)));
            if (encoded.getBytes(StandardCharsets.UTF_8).length > Math.min(claim.snapshot().policy().maxOutputBytes(), claim.invocation().request().options().maxOutputBytes())) {
                review(job, "OUTCOME_RESULT_LIMIT");
                release(job);
                return true;
            }
            String digest = CanonicalJson.sha256(encoded), evidenceKey = key(evidence.reference(), evidence.digest(), evidence.decision().name());
            String resultKey = "tool-outcome-" + evidenceKey, id = key(job.ownerKey(), job.invocationId(), job.attemptId(), resultKey);
            check(payload.result(id) == null);
            check(payload.insertResult(id, job.ownerKey(), job.invocationKey(), key(job.attemptId()), key(resultKey), "tool-result", result.outcome() == ToolResult.Outcome.SUCCEEDED ? 0 : 1, digest, encoded) == 1);
            var ref = new ResultRef(id, "tool-result", 1, digest, result.outcome() != ToolResult.Outcome.SUCCEEDED);
            var terminal = result.outcome() == ToolResult.Outcome.SUCCEEDED ? Invocation.State.SUCCEEDED : Invocation.State.FAILED;
            if (evidence.decision() == ToolOutcomeEvidence.Decision.PROVEN_NOT_EXECUTED && executions.cancellationRequestedInCurrentTransaction(claim.invocation()))
                terminal = Invocation.State.CANCELLED;
            check(jobs.evidence(job.invocationKey(), evidenceKey, job.checks(), evidence.decision().name(), evidence.reference(), evidence.digest(), digest, at) == 1);
            var completion = new ExecutionCommands.Complete(ExecutionCommands.Guard.from(claim.owner(), claim.invocation(), claim.attempt()),
                    "tool-outcome-" + evidenceKey, new ExecutionPayload.Terminal(terminal, ref, result.error()), claim.attempt().usage(), "tool-outcome-evidence:" + evidenceKey);
            check(executions.completeToolOutcomeInCurrentTransaction(completion).successful());
            save(job, job.changed(State.RESOLVED, job.checks(), "", job.fence(), 0, 0, job.nextCheck(), evidence.decision().name(), evidenceKey));
            release(job);
            return true;
        });
    }

    private void validateResult(Claim claim, ToolOutcomeEvidence evidence) {
        var result = evidence.result();
        // 当前受信本地契约没有来源或产物；其他类型需实现其归属／完整性核验后再开放。
        check(result.sources().isEmpty() && result.artifacts().isEmpty());
        check(result.callId().equals(claim.snapshot().request().input().callId()));
        check(switch (evidence.decision()) {
            case CONFIRMED_SUCCEEDED -> result.outcome() == ToolResult.Outcome.SUCCEEDED;
            case CONFIRMED_FAILED -> result.outcome() == ToolResult.Outcome.FAILED;
            case PROVEN_NOT_EXECUTED ->
                    result.outcome() == ToolResult.Outcome.FAILED && result.sideEffect() == com.arte.ainew.common.execution.ExecutionError.SideEffect.NONE;
            case STILL_UNKNOWN -> false;
        });
        check(ToolExecutionRules.resultAllowed(claim.snapshot().binding().tool().tool().risk(), result));
        var ref = claim.snapshot().binding().tool().tool().outputSchema();
        var schema = claim.snapshot().schemas().stream().filter(s -> s.definition().equals(ref)).findFirst().orElseThrow();
        check(ToolCatalogDigests.schema(schema.document()).equals(schema.checksum()));
        if (result.output() != null)
            check(new ToolSchemaValidator().compile(ref, schema.document(), ToolSchemaValidator.Purpose.OUTPUT).validate(result.output(), claim.invocation().request().options().maxOutputBytes()).valid());
    }

    private void release(Case job) {
        check(capacity.release(permitKey(job), job.fence()) == 1);
    }
}
