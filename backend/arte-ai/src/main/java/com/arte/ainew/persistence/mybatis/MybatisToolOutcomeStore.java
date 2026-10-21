package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.tool.outcome.ToolOutcomeQueryRegistry;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.config.NewAiSchedulingProperties;
import com.arte.ainew.persistence.mybatis.mapper.*;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.spi.auth.ToolManagementAccess;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ToolOutcomeStore;
import com.arte.ainew.spi.persistence.ToolReconciliationStore;
import com.arte.core.enums.ResultCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
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
public final class MybatisToolOutcomeStore implements ToolOutcomeStore, ToolReconciliationStore {

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
        check(inv.state() == Invocation.State.UNKNOWN && inv.request().kind() == CapabilityDescriptor.Kind.TOOL);
        if (inv.activeAttemptId() == null)
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
                if (inv != null && inv.state() == Invocation.State.UNKNOWN && inv.request().kind() == CapabilityDescriptor.Kind.TOOL) {
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
            confirmEvidence(claim, evidence);
            release(job);
            return true;
        });
    }

    /**
     * 自动与人工完成共用同一结果／证据／终态事务；调用方须持原调用、工作项和 Attempt fencing。
     */
    private boolean confirmEvidence(Claim claim, ToolOutcomeEvidence evidence) {
        var job = claim.work();
        long at = now();
        check(evidence.owner().equals(claim.owner()) && evidence.invocationId().equals(job.invocationId())
                && evidence.attemptId().equals(job.attemptId()) && evidence.operationKey().equals(job.operationKey()));
        var result = evidence.result();
        validateResult(claim, evidence);
        String encoded = CanonicalJson.normalize(codec.encode(new InvocationResult.Tool(result)));
        if (encoded.getBytes(StandardCharsets.UTF_8).length > Math.min(claim.snapshot().policy().maxOutputBytes(), claim.invocation().request().options().maxOutputBytes())) {
            review(job, "OUTCOME_RESULT_LIMIT");
            return false;
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
        return true;
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

    private static AdmissionException reject(ResultCodeEnum code) {
        return new AdmissionException(code);
    }

    private void authorizeReview(ToolManagementAccess.Permit permit, Invocation inv, boolean managed) {
        permit.requireInCurrentTransaction(Set.of(AdmissionAuthorization.READ));
        var original = ExecutionOwner.from(inv.request().context());
        var actor = permit.owner();
        if (!original.tenantId().equals(actor.tenantId()) || !original.workspaceId().equals(actor.workspaceId())
                || (!managed && !original.subjectId().equals(actor.subjectId())))
            throw reject(ResultCodeEnum.AI_NOT_FOUND);
        if (managed && permit.operation() != ToolManagementAccess.Operation.RECONCILE)
            throw new AccessDeniedException("Current reconciliation authority required");
    }

    private ToolReconciliation.Review reviewInfo(Case job) {
        var review = jobs.review(job.invocationKey());
        return review != null ? review : new ToolReconciliation.Review(job.invocationKey(), "", 0, "核验原操作证据；无法判定时记录原因与下一步", job.validUntil() + Duration.ofDays(1).toMillis(), 0);
    }

    private void saveReview(ToolReconciliation.Review review) {
        check((jobs.review(review.invocationKey()) == null ? jobs.insertReview(review) : jobs.saveReview(review)) == 1);
    }

    private ToolReconciliation.Detail detail(ToolManagementAccess.Permit permit, String id, boolean managed) {
        var inv = invocation(key(id));
        if (inv == null) throw reject(ResultCodeEnum.AI_NOT_FOUND);
        authorizeReview(permit, inv, managed);
        var job = jobs.find(key(id), true);
        if (job == null || !job.invocationId().equals(id)) throw reject(ResultCodeEnum.AI_NOT_FOUND);
        check(job.ownerKey().equals(owner(inv)) && job.attemptId().equals(inv.activeAttemptId()));
        var fixed = snapshot(inv);
        var r = reviewInfo(job);
        var currentAttempt = codec.decode(Objects.requireNonNull(execution.attemptSnapshot(key(job.attemptId()), true)), Attempt.class);
        boolean attemptAvailable = !"RECONCILE".equals(execution.attemptPurpose(key(job.attemptId()))) || currentAttempt.leaseExpiresAt().toEpochMilli() <= now();
        boolean may = attemptAvailable && managed && !permit.owner().subjectId().equals(ExecutionOwner.from(inv.request().context()).subjectId())
                && permit.owner().subjectId().equals(r.assignee()) && inv.state() == Invocation.State.UNKNOWN && job.leaseUntil() <= now();
        return new ToolReconciliation.Detail(job, inv, fixed, r, may, managed && !permit.owner().subjectId().equals(ExecutionOwner.from(inv.request().context()).subjectId()) && job.state() != State.RESOLVED && job.leaseUntil() <= now(), !managed || registry.find(fixed).isPresent(), registry.find(fixed).isPresent(), now(), managed ? jobs.history(job.invocationKey()) : List.of());
    }

    public Mono<ToolReconciliation.Detail> reconciliation(ToolManagementAccess.Permit permit, String id, boolean managed) {
        ContractChecks.id(id, "invocationId");
        return io(() -> detail(permit, id, managed));
    }

    public Mono<ToolReconciliation.Page> reconciliations(ToolManagementAccess.Permit permit, String after, int limit) {
        if (after != null) ContractChecks.digest(after, "cursor");
        ContractChecks.range(limit, "review page size", 1, 20);
        // 候选索引与每条详情分开短事务，避免持治理锁再锁下一条 Invocation 的顺序倒置。
        return io(() -> {
            permit.requireInCurrentTransaction(Set.of(AdmissionAuthorization.READ));
            check(permit.operation() == ToolManagementAccess.Operation.RECONCILE);
            return jobs.page(key(permit.owner().tenantId(), permit.owner().workspaceId()), after == null ? "" : after, limit + 1);
        }).flatMap(rows -> Flux.fromIterable(rows.stream().limit(limit).toList())
                .concatMap(r -> reconciliation(permit, r.invocationId(), true)).collectList()
                .map(visible -> new ToolReconciliation.Page(visible, rows.size() <= limit || visible.isEmpty() ? null : visible.getLast().work().invocationKey(), rows.size() <= limit)));
    }

    private ToolReconciliation.Receipt receipt(ToolReconciliation.Audit a, String id) {
        return new ToolReconciliation.Receipt(id, a.outcome(), Long.toString(a.caseVersion()), Long.toString(a.invocationVersion()), a.caseState(), a.invocationState());
    }

    /**
     * 人工命令与自动核对竞争同一原调用锁；重复命令也须重新经过当前授权，绝不接受原 owner 自审。
     */
    public Mono<ToolReconciliation.Receipt> reviewCommand(ToolManagementAccess.Permit permit, String commandKey, ToolReconciliation.Command command) {
        ContractChecks.id(commandKey, "Idempotency-Key");
        return io(() -> {
            var d = detail(permit, command.invocationId(), true);
            var job = d.work();
            var inv = d.invocation();
            var actor = permit.owner().subjectId();
            String cmdKey = key("tool-review", permit.owner().tenantId(), permit.owner().workspaceId(), actor, commandKey), digest = CanonicalJson.digest(command);
            // 不同原调用上也统一按命令键串行，防止同一主体并发复用相同键。
            system.ensureLock(key("tool-review-command", cmdKey));
            var previous = jobs.command(cmdKey);
            if (previous != null) {
                if (!previous.actorId().equals(actor) || !previous.invocationKey().equals(job.invocationKey()) || !previous.requestDigest().equals(digest))
                    throw reject(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT);
                return receipt(previous, command.invocationId());
            }
            if (!command.attemptId().equals(job.attemptId()) || !command.operationKey().equals(job.operationKey()))
                throw new IllegalArgumentException("Original outcome identity mismatch");
            if (job.version() != command.expectedVersion() || inv.version() != command.expectedInvocationVersion())
                throw reject(ResultCodeEnum.AI_VERSION_CONFLICT);
            boolean self = actor.equals(ExecutionOwner.from(inv.request().context()).subjectId());
            if (self && command.action() != ToolReconciliation.Action.REQUEST_CHECK)
                throw new AccessDeniedException("Requester cannot verify own outcome");
            long at = now();
            String outcome = "APPLIED";
            var r = d.review();
            if (command.action() == ToolReconciliation.Action.CLAIM) {
                if (job.state() == State.RESOLVED || job.leaseUntil() > at)
                    throw reject(ResultCodeEnum.AI_VERSION_CONFLICT);
                saveReview(new ToolReconciliation.Review(job.invocationKey(), actor, at, command.nextStep(), r.dueAt(), r.alertedAt()));
                save(job, job.changed(job.state(), job.checks(), job.workerId(), job.fence(), job.leaseUntil(), job.queryDeadline(), job.nextCheck(), "REVIEW_ASSIGNED", job.evidenceKey()));
            } else if (command.action() == ToolReconciliation.Action.REQUEST_CHECK) {
                // 只记录请求并提示后台；不抢占租约、不绕过退避、不恢复耗尽／无法核对的任务。
                outcome = job.state() == State.CHECKING && inv.state() == Invocation.State.UNKNOWN && at < job.validUntil() && job.checks() < DELAYS.size() ? "SCHEDULED" : "NEEDS_REVIEW";
            } else {
                if (!actor.equals(r.assignee())) throw new AccessDeniedException("Claim this case before verification");
                if (job.leaseUntil() > at) throw reject(ResultCodeEnum.AI_VERSION_CONFLICT);
                var a = codec.decode(Objects.requireNonNull(execution.attemptSnapshot(key(job.attemptId()), true)), Attempt.class);
                if (inv.state() == Invocation.State.UNKNOWN && "RECONCILE".equals(execution.attemptPurpose(key(job.attemptId()))) && a.leaseExpiresAt().toEpochMilli() > at)
                    throw reject(ResultCodeEnum.AI_VERSION_CONFLICT);
                ToolOutcomeEvidence verified = null;
                if (command.action() == ToolReconciliation.Action.CONFIRM)
                    verified = verifyProposal(new Claim(job, inv, a, d.snapshot()), command.proposal());
                if (inv.state() != Invocation.State.UNKNOWN) {
                    // 新的相反证据作为冲突审计，原已确认结果不改写；责任池保留人工复核任务。
                    if (command.action() != ToolReconciliation.Action.CONFIRM)
                        throw reject(ResultCodeEnum.AI_VERSION_CONFLICT);
                    var stored = inv.result() == null ? null : payload.result(inv.result().resultId());
                    var oldResult = stored == null ? null : codec.decode(stored.workspaceSnapshot(), InvocationResult.class);
                    if (oldResult instanceof InvocationResult.Tool oldTool && oldTool.value().equals(verified.result()))
                        outcome = "ALREADY_RESOLVED";
                    else {
                        save(job, job.changed(State.NEEDS_REVIEW, job.checks(), "", job.fence(), 0, 0, job.nextCheck(), "OUTCOME_EVIDENCE_CONFLICT", job.evidenceKey()));
                        outcome = "EVIDENCE_CONFLICT";
                    }
                } else if (command.action() == ToolReconciliation.Action.UNRESOLVABLE) {
                    save(job, job.changed(State.UNRESOLVABLE, job.checks(), "", job.fence(), 0, 0, job.nextCheck(), "MANUALLY_UNRESOLVABLE", job.evidenceKey()));
                } else {
                    ContractChecks.id(job.operationKey(), "original operationKey");
                    var attempt = executions.claimToolOutcomeInCurrentTransaction(inv, "tool-review-" + key(actor), LEASE);
                    var claim = new Claim(job, inv, attempt, d.snapshot());
                    if (!confirmEvidence(claim, verified)) outcome = "NEEDS_REVIEW";
                }
                saveReview(new ToolReconciliation.Review(job.invocationKey(), r.assignee(), r.assignedAt(), command.nextStep(), r.dueAt(), r.alertedAt()));
            }
            var finalJob = jobs.find(job.invocationKey(), true);
            var finalInv = invocation(job.invocationKey());
            var p = command.proposal();
            var audit = new ToolReconciliation.Audit(cmdKey, job.invocationKey(), actor, digest, command.action().name(), outcome, finalJob.version(), finalInv.version(), finalJob.state().name(), finalInv.state().name(), job.attemptId(), job.operationKey(),
                    p == null ? "" : p.source().name(), p == null ? "" : p.decision().name(), p == null ? "" : p.reference(), p == null ? "" : p.digest(), command.reason() + (p == null ? "" : "\n" + p.verificationNote()), command.nextStep(), at);
            check(jobs.insertCommand(audit) == 1);
            return receipt(audit, command.invocationId());
        });
    }

    private ToolOutcomeEvidence verifyProposal(Claim claim, ToolReconciliation.Proposal p) {
        ContractChecks.id(claim.work().operationKey(), "original operationKey");
        if (p.source() != ToolReconciliation.Source.DOMAIN_RECEIPT)
            throw new IllegalArgumentException("No reviewed external evidence verifier is installed for this tool");
        var adapter = registry.find(claim.snapshot()).orElseThrow(() -> new IllegalArgumentException("No trusted domain evidence verifier"));
        var evidence = adapter.readEvidenceInCurrentTransaction(claim);
        if (evidence.decision() == ToolOutcomeEvidence.Decision.STILL_UNKNOWN)
            throw new IllegalArgumentException("Missing receipt does not prove an outcome");
        if (evidence.decision() != p.decision() || !evidence.reference().equals(p.reference()) || !evidence.digest().equals(p.digest())
                || evidence.result().sideEffect() != p.sideEffect() || !Objects.equals(evidence.result().output(), p.output()))
            throw new IllegalArgumentException("Evidence differs from authoritative receipt");
        return evidence;
    }

    /**
     * 给已认领的独立复核人返回数据库权威回执材料，不消费自动查询额度，也不能触发写操作。
     */
    public Mono<ToolOutcomeEvidence> reviewEvidence(ToolManagementAccess.Permit permit, String id) {
        return io(() -> {
            var d = detail(permit, id, true);
            if (!d.mayConfirm()) throw new AccessDeniedException("Independent assigned reviewer required");
            if (d.work().leaseUntil() > now()) throw reject(ResultCodeEnum.AI_VERSION_CONFLICT);
            var attempt = codec.decode(execution.attemptSnapshot(key(d.work().attemptId()), true), Attempt.class);
            var claim = new Claim(d.work(), d.invocation(), attempt, d.snapshot());
            return registry.find(d.snapshot()).orElseThrow(() -> new IllegalArgumentException("No domain receipt verifier"))
                    .readEvidenceInCurrentTransaction(claim);
        });
    }

    public Mono<Integer> alertReviews() {
        return io(() -> {
            int count = 0;
            long at = now();
            for (var candidate : jobs.alerts(at, at - Duration.ofMinutes(5).toMillis())) {
                var inv = invocation(candidate.invocationKey());
                var job = jobs.find(candidate.invocationKey(), true);
                if (inv == null || job == null || job.state() != State.NEEDS_REVIEW && job.state() != State.UNRESOLVABLE)
                    continue;
                var r = reviewInfo(job);
                if (r.alertedAt() > at - Duration.ofMinutes(5).toMillis()) continue;
                saveReview(new ToolReconciliation.Review(r.invocationKey(), r.assignee(), r.assignedAt(), r.nextStep(), r.dueAt(), at));
                log.warn("AI_TOOL_REVIEW_ATTENTION invocationId={} state={} unassigned={} overdue={}", job.invocationId(), job.state(), r.assignee().isEmpty(), r.dueAt() <= at);
                count++;
            }
            return count;
        });
    }

    private void release(Case job) {
        check(capacity.release(permitKey(job), job.fence()) == 1);
    }
}
