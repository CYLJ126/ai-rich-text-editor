package com.arte.ainew.persistence.mybatis;

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
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static com.arte.ainew.pojo.execution.StoreOutcome.Code.*;
import static com.arte.ainew.serialization.CanonicalJson.key;

/**
 * TOOL 受理事务：先当前宿主资格，再重放锁／绑定治理，再复用通用受理提交；快照写入失败连同首事件／Outbox 整体回滚。
 * 所有依赖必须共享同一物理 DataSource，不以异步串联的两个 Store 提交模拟原子性。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 16:30 ✾
 */
public final class MybatisToolAdmissionStore implements ToolAdmissionStore {

    public static final int MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024;
    private final ToolAdmissionMapper mapper;
    private final ExecutionMapper execution;
    private final SystemMapper locks;
    private final MybatisExecutionPersistence executions;
    private final MybatisToolBindingStore bindings;
    private final MybatisToolCatalogStore catalog;
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
        this.catalog = catalog;
        this.codec = Objects.requireNonNull(codec);
        this.scheduler = Objects.requireNonNull(scheduler);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(source));
        transaction.setTimeout(30);
    }

    private <T> Mono<T> io(Supplier<T> work) {
        return Mono.fromCallable(() -> {
            if (Schedulers.isInNonBlockingThread())
                throw new IllegalStateException("Tool admission requires a bounded JDBC worker");
            return transaction.execute(status -> work.get());
        }).subscribeOn(scheduler);
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
            authorization.accept(candidate);
            var context = candidate.request().context();
            var owner = ExecutionOwner.from(context);
            String ownerKey = ownerKey(owner), bindingKey = key(candidate.input().bindingId()), idem = key(context.idempotencyKey());
            String lock = key("tool-admission", ownerKey, bindingKey, idem);
            locks.ensureLock(lock);
            locks.lock(lock);
            var previous = accepted(mapper.accepted(ownerKey, bindingKey, idem));
            if (previous != null) {
                if (!previous.snapshot().submissionDigest().equals(candidate.submissionDigest()))
                    throw new MybatisExecutionPersistence.Rejected(IDEMPOTENCY_CONFLICT);
                if (!previous.snapshot().equals(candidate)) authorization.accept(previous.snapshot());
                var fixed = bindings.fixedInCurrentTransaction(owner, previous.snapshot().binding().definition());
                if (!fixed.successful() || !fixed.value().equals(previous.snapshot().binding()))
                    throw new MybatisExecutionPersistence.Rejected(INVALID_STATE);
                return StoreOutcome.replayed(previous);
            }
            // 新受理重新按当前绑定／审核兼容链解析，拒绝预检后发生的配置或选版变化；重放不走此分支。
            var current = bindings.resolveInCurrentTransaction(owner, candidate.input().bindingId());
            if (!current.successful()) throw new MybatisExecutionPersistence.Rejected(INVALID_STATE);
            if (!current.value().equals(candidate.binding()))
                throw new MybatisExecutionPersistence.Rejected(VERSION_CONFLICT);
            var artifacts = catalog.releaseArtifacts(candidate.binding().tool().tool().definition());
            if (artifacts == null || !new HashSet<>(artifacts.schemas()).equals(new HashSet<>(candidate.schemas())))
                throw new IllegalStateException("Tool execution schema integrity mismatch");
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
            return StoreOutcome.applied(new Accepted(outcome.value(), candidate));
        })).onErrorResume(MybatisExecutionPersistence.Rejected.class, rejected -> Mono.just(StoreOutcome.rejected(rejected.code)));
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
