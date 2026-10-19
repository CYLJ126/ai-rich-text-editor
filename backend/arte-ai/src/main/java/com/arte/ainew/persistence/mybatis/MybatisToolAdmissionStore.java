package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.application.support.ToolObservation;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.persistence.mybatis.mapper.ExecutionMapper;
import com.arte.ainew.persistence.mybatis.mapper.SystemMapper;
import com.arte.ainew.persistence.mybatis.mapper.ToolAdmissionMapper;
import com.arte.ainew.pojo.execution.ExecutionCommands;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.ToolExecutionSnapshot;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ToolAdmissionStore;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import com.arte.core.diagnostics.OperationObservation;
import lombok.extern.slf4j.Slf4j;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.Ordered;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.arte.ainew.application.support.ToolObservation.Stage.*;
import static com.arte.ainew.pojo.execution.StoreOutcome.Code.*;
import static com.arte.ainew.serialization.CanonicalJson.key;

/**
 * TOOL 受理事务：先当前宿主资格，再重放锁／绑定治理，再复用通用受理提交；快照写入失败连同首事件／Outbox 整体回滚。
 * 所有依赖必须共享同一物理 DataSource，不以异步串联的两个 Store 提交模拟原子性。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 16:30 ✾
 */
@Slf4j
public final class MybatisToolAdmissionStore implements ToolAdmissionStore {

    public static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    private final ToolAdmissionMapper mapper;
    private final ExecutionMapper execution;
    private final SystemMapper locks;
    private final MybatisExecutionPersistence executions;
    private final MybatisToolBindingStore bindings;
    private final ExecutionRecordCodec codec;
    private final Scheduler scheduler;
    private final TransactionTemplate transaction;
    private final Clock clock;

    /**
     * 构造不读写数据库；不创建表、不调用工具，Scheduler 由调用方释放。
     */
    public MybatisToolAdmissionStore(DataSource source, ExecutionRecordCodec codec, Scheduler scheduler,
                                     MybatisExecutionPersistence executions, MybatisToolBindingStore bindings,
                                     MybatisToolCatalogStore catalog) {
        this(source, codec, scheduler, executions, bindings, catalog, Clock.systemUTC());
    }

    /**
     * 显式时钟便于验证锁等待也消耗总期限；不对历史重放重新计时。
     */
    public MybatisToolAdmissionStore(DataSource source, ExecutionRecordCodec codec, Scheduler scheduler,
                                     MybatisExecutionPersistence executions, MybatisToolBindingStore bindings,
                                     MybatisToolCatalogStore catalog, Clock clock) {
        this.clock = Objects.requireNonNull(clock);
        ContractChecks.require(executions.uses(source) && bindings.uses(source) && catalog.uses(source),
                "Tool admission dependencies must use the same physical DataSource");
        var sessions = new SqlSessionTemplate(ExecutionSqlSessionFactory.create(source));
        mapper = sessions.getMapper(ToolAdmissionMapper.class);
        execution = sessions.getMapper(ExecutionMapper.class);
        locks = sessions.getMapper(SystemMapper.class);
        this.executions = executions;
        this.bindings = bindings;
        this.codec = Objects.requireNonNull(codec);
        this.scheduler = Objects.requireNonNull(scheduler);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(30);
    }

    private <T> Mono<T> io(Supplier<T> work) {
        return io(work, null, null, null);
    }

    private <T> Mono<T> io(Supplier<T> work, String invocationId, String traceId, Instant deadline) {
        return Mono.defer(() -> {
            var cancelled = new AtomicBoolean();
            // 仅屏蔽本次订阅对已运行 JDBC 的中断；cache 持有其终态，避免迟到异常掉入全局 onErrorDropped。
            // 不跨请求缓存受理结果；未开始的已取消任务直接跳过，已开始的事务仍须在末尾检查 deadline 并回滚。
            return Mono.fromCallable(() -> {
                if (cancelled.get()) return null;
                if (Schedulers.isInNonBlockingThread())
                    throw new IllegalStateException("Tool admission requires a bounded JDBC worker");
                return invocationId == null ? transaction.execute(status -> work.get())
                        : OperationObservation.call(ADMISSION_TRANSACTION.name(), invocationId, traceId, deadline,
                        () -> transaction.execute(status -> work.get()));
            }).subscribeOn(scheduler).doOnError(error -> {
                if (!cancelled.get()) return;
                if (error instanceof DeadlineExceeded || error instanceof CancellationException)
                    log.debug("AI_TOOL_ADMISSION finished after caller cancellation, invocationId={}, traceId={}, reason={}",
                            invocationId, traceId, error.getClass().getSimpleName());
                else
                    log.warn("AI_TOOL_ADMISSION failed after caller cancellation, invocationId={}, traceId={}, type={}",
                            invocationId, traceId, error.getClass().getName());
            }).cache().doOnCancel(() -> cancelled.set(true));
        });
    }

    @Override
    public Mono<Accepted> findAccepted(ExecutionOwner owner, String bindingId, String idempotencyKey) {
        Objects.requireNonNull(owner);
        ContractChecks.id(bindingId, "bindingId");
        ContractChecks.id(idempotencyKey, "idempotencyKey");
        return io(() -> accepted(mapper.accepted(ownerKey(owner), key(bindingId), key(idempotencyKey))));
    }

    @Override
    public Mono<ToolExecutionSnapshot> snapshot(ExecutionOwner owner, String invocationId) {
        Objects.requireNonNull(owner);
        ContractChecks.id(invocationId, "invocationId");
        return io(() -> {
            var found = accepted(mapper.toolSnapshot(key(invocationId), ownerKey(owner)));
            return found == null ? null : found.snapshot();
        });
    }

    @Override
    public Mono<StoreOutcome<Accepted>> accept(ToolExecutionSnapshot candidate, Consumer<ToolExecutionSnapshot> authorization) {
        Objects.requireNonNull(candidate);
        Objects.requireNonNull(authorization);
        // 大记录编码在持锁前完成；不会在写完执行数据后才发现快照超限。
        return Mono.fromCallable(() -> {
                    String encoded = CanonicalJson.normalize(codec.encode(candidate));
                    ContractChecks.require(encoded.getBytes(StandardCharsets.UTF_8).length <= MAX_SNAPSHOT_BYTES, "Tool execution snapshot exceeds byte limit");
                    return encoded;
                }).subscribeOn(scheduler).flatMap(encoded -> io(() -> {
                    var context = candidate.request().context();
                    var newlyAccepted = new AtomicBoolean();
                    observeTransaction(context, newlyAccepted);
                    ToolObservation.call(COMMIT_AUTHORIZATION, context, () -> {
                        authorization.accept(candidate);
                        return true;
                    });
                    var owner = ExecutionOwner.from(context);
                    String ownerKey = ownerKey(owner), bindingKey = key(candidate.input().bindingId()), idem = key(context.idempotencyKey());
                    String lock = key("tool-admission", ownerKey, bindingKey, idem);
                    locks.ensureLock(lock);
                    var previous = accepted(mapper.accepted(ownerKey, bindingKey, idem));
                    if (previous != null) {
                        if (!previous.snapshot().submissionDigest().equals(candidate.submissionDigest()))
                            throw new MybatisExecutionPersistence.Rejected(IDEMPOTENCY_CONFLICT);
                        if (!previous.snapshot().equals(candidate)) ToolObservation.call(COMMIT_AUTHORIZATION, context, () -> {
                            authorization.accept(previous.snapshot());
                            return true;
                        });
                        var fixed = ToolObservation.call(COMMIT_GOVERNANCE, context,
                                () -> bindings.fixedInCurrentTransaction(owner, previous.snapshot().binding().definition()));
                        if (!fixed.successful() || !fixed.value().equals(previous.snapshot().binding()))
                            throw new MybatisExecutionPersistence.Rejected(INVALID_STATE);
                        return StoreOutcome.replayed(previous);
                    }
                    // 新受理重新按当前绑定／审核兼容链解析，拒绝预检后发生的配置或选版变化；重放不走此分支。
                    var loaded = new AtomicReference<ToolCatalogStore.Batch>();
                    var current = ToolObservation.call(COMMIT_GOVERNANCE, context,
                            () -> bindings.resolveInCurrentTransaction(owner, candidate.input().bindingId(), loaded::set));
                    if (!current.successful()) throw new MybatisExecutionPersistence.Rejected(INVALID_STATE);
                    if (!current.value().equals(candidate.binding()))
                        throw new MybatisExecutionPersistence.Rejected(VERSION_CONFLICT);
                    var artifacts = loaded.get();
                    if (artifacts == null || !new HashSet<>(artifacts.schemas()).equals(new HashSet<>(candidate.schemas())))
                        throw new IllegalStateException("Tool execution schema integrity mismatch");
                    return ToolObservation.call(COMMIT_WRITES, context, () -> {
                        requireUnexpired(candidate);
                        var now = candidate.receivedAt();
                        var invocation = new Invocation(candidate.request(), candidate.requestDigest(), null, null, null,
                                Invocation.State.ACCEPTED, 0, null, null, null, now, now);
                        var outcome = executions.acceptInCurrentTransaction(new ExecutionCommands.Accept(invocation, null, executions.toolDispatchPlan(candidate.binding().tool().tool().providerId(),
                                candidate.binding().tool().tool().defaultPolicy().timeout())));
                        if (outcome.code() != APPLIED)
                            throw new IllegalStateException("Generic acceptance has no matching tool snapshot");
                        var row = new ToolAdmissionMapper.Row(key(context.executionId()), ownerKey, bindingKey, idem,
                                candidate.submissionDigest(), candidate.requestDigest(), CanonicalJson.sha256(encoded), encoded);
                        if (mapper.insertToolSnapshot(row) != 1)
                            throw new IllegalStateException("Tool execution snapshot was not recorded");
                        requireUnexpired(candidate);
                        newlyAccepted.set(true);
                        return StoreOutcome.applied(new Accepted(outcome.value(), candidate));
                    });
                }, candidate.request().context().executionId(), candidate.request().context().traceId(), candidate.request().options().deadline()))
                .onErrorResume(MybatisExecutionPersistence.Rejected.class, rejected -> Mono.just(StoreOutcome.rejected(rejected.code)));
    }

    /**
     * 真实物理提交后、既有唤醒之前记录提交点；回滚或未知提交状态分别记录，HTTP 取消不代替事务结果。
     */
    private static void observeTransaction(ExecutionContext context, AtomicBoolean newlyAccepted) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public int getOrder() {
                return Ordered.HIGHEST_PRECEDENCE;
            }

            @Override
            public void afterCommit() {
                ToolObservation.point(newlyAccepted.get() ? ADMISSION_COMMITTED : ADMISSION_REPLAY_COMMITTED, context);
            }

            @Override
            public void afterCompletion(int status) {
                if (status != STATUS_COMMITTED)
                    ToolObservation.point(status == STATUS_ROLLED_BACK ? ADMISSION_ROLLED_BACK : ADMISSION_OUTCOME_UNKNOWN, context);
            }
        });
    }

    private void requireUnexpired(ToolExecutionSnapshot snapshot) {
        if (!clock.instant().isBefore(snapshot.request().options().deadline())) throw new DeadlineExceeded();
    }

    private Accepted accepted(ToolAdmissionMapper.Row row) {
        if (row == null) return null;
        if (!CanonicalJson.sha256(row.snapshot()).equals(row.payloadDigest()))
            throw new IllegalStateException("Tool snapshot bytes are corrupt");
        var snapshot = codec.decode(row.snapshot(), ToolExecutionSnapshot.class);
        var request = snapshot.request();
        if (!row.invocationKey().equals(key(request.context().executionId())) || !row.ownerKey().equals(ownerKey(ExecutionOwner.from(request.context())))
                || !row.bindingKey().equals(key(snapshot.input().bindingId())) || !row.idempotencyKey().equals(key(request.context().idempotencyKey()))
                || !row.submissionDigest().equals(snapshot.submissionDigest()) || !row.requestDigest().equals(snapshot.requestDigest()))
            throw new IllegalStateException("Tool snapshot metadata mismatch");
        var invocation = codec.decode(Objects.requireNonNull(execution.invocationSnapshot(row.invocationKey(), false),
                "Tool snapshot invocation is missing"), Invocation.class);
        return new Accepted(invocation, snapshot);
    }

    private static String ownerKey(ExecutionOwner owner) {
        return key(owner.tenantId(), owner.workspaceId(), owner.subjectId());
    }
}
