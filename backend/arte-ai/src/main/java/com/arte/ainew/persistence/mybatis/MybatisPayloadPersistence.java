package com.arte.ainew.persistence.mybatis;

import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.persistence.mybatis.mapper.ExecutionMapper;
import com.arte.ainew.persistence.mybatis.mapper.PayloadMapper;
import com.arte.ainew.persistence.mybatis.mapper.PersistenceRows.PayloadRow;
import com.arte.ainew.persistence.mybatis.mapper.SystemMapper;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.tool.ToolResult;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.persistence.ContextSnapshotStore;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ExecutionResultStore;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.Supplier;

import static com.arte.ainew.pojo.execution.StoreOutcome.Code.*;
import static com.arte.ainew.serialization.CanonicalJson.key;

/**
 * 隔离的不可变字节存储，数据库行锁防重；不推进执行状态，不执行 DDL，不持有跨响应式边界事务。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
public final class MybatisPayloadPersistence implements ContextSnapshotStore, ExecutionResultStore {

    private final PayloadMapper payloadMapper;
    private final ExecutionMapper executionMapper;
    private final SystemMapper systemMapper;
    private final ExecutionRecordCodec executionRecordCodec;
    private final TransactionTemplate transactionTemplate;
    private final Scheduler scheduler;

    public MybatisPayloadPersistence(DataSource dataSource, ExecutionRecordCodec executionRecordCodec, Scheduler scheduler) {
        var sessions = new SqlSessionTemplate(ExecutionSqlSessionFactory.create(dataSource));
        payloadMapper = sessions.getMapper(PayloadMapper.class);
        executionMapper = sessions.getMapper(ExecutionMapper.class);
        systemMapper = sessions.getMapper(SystemMapper.class);
        transactionTemplate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactionTemplate.setTimeout(30);
        this.executionRecordCodec = Objects.requireNonNull(executionRecordCodec);
        this.scheduler = Objects.requireNonNull(scheduler);
    }

    private <T> Mono<T> tx(Supplier<T> work) {
        return Mono.fromCallable(() -> {
            if (Schedulers.isInNonBlockingThread()) {
                throw new IllegalStateException("Blocking payload store requires a bounded worker");
            }
            return transactionTemplate.execute(status -> work.get());
        }).subscribeOn(scheduler);
    }

    private void lock(String id) {
        systemMapper.ensureLock(id);
        systemMapper.lock(id);
    }

    private static String ownerKey(ExecutionOwner owner) {
        Objects.requireNonNull(owner, "owner");
        return key(owner.tenantId(), owner.workspaceId(), owner.subjectId());
    }

    private String bytes(Object value) {
        return CanonicalJson.normalize(executionRecordCodec.encode(value));
    }

    private void integrity(PayloadRow row) {
        if (row.schemaVersion() != 1 || row.partial() < 0 || row.partial() > 1
                || !row.payloadDigest().equals(CanonicalJson.sha256(row.snapshot()))) {
            throw new IllegalStateException("Payload integrity verification failed");
        }
    }

    @Override
    public Mono<StoreOutcome<ContextSnapshot>> put(ExecutionOwner owner, ContextSnapshot snapshot) {
        var ownerId = ownerKey(owner);
        Objects.requireNonNull(snapshot, "snapshot");
        return tx(() -> {
            var id = key(ownerId, snapshot.snapshotId());
            lock(key("context-payload", id));
            var encoded = bytes(snapshot);
            var digest = CanonicalJson.sha256(encoded);
            var existing = payloadMapper.context(id);
            if (existing != null) {
                integrity(existing);
                if (!ownerId.equals(existing.ownerKey()) || !digest.equals(existing.payloadDigest())) {
                    return StoreOutcome.rejected(IDEMPOTENCY_CONFLICT);
                }
                return StoreOutcome.replayed(executionRecordCodec.decode(existing.snapshot(), ContextSnapshot.class));
            }
            payloadMapper.insertContext(id, ownerId, digest, encoded);
            return StoreOutcome.applied(snapshot);
        });
    }

    @Override
    public Mono<ContextSnapshot> find(ExecutionOwner owner, String snapshotId) {
        var ownerId = ownerKey(owner);
        ContractChecks.id(snapshotId, "snapshotId");
        return tx(() -> {
            var row = payloadMapper.context(key(ownerId, snapshotId));
            if (row == null || !row.ownerKey().equals(ownerId)) {
                return null;
            }
            integrity(row);
            var snapshot = executionRecordCodec.decode(row.snapshot(), ContextSnapshot.class);
            if (!snapshot.snapshotId().equals(snapshotId)) {
                throw new IllegalStateException("Snapshot identity mismatch");
            }
            return snapshot;
        });
    }

    @Override
    public Mono<StoreOutcome<ResultRef>> put(ExecutionOwner owner, String invocationId, String attemptId,
                                             String resultKey, InvocationResult result) {
        var ownerId = ownerKey(owner);
        ContractChecks.id(invocationId, "invocationId");
        ContractChecks.id(attemptId, "attemptId");
        ContractChecks.id(resultKey, "resultKey");
        Objects.requireNonNull(result, "result");
        return tx(() -> {
            var id = key(ownerId, invocationId, attemptId, resultKey);
            lock(key("result-payload", id));
            var invocationBytes = executionMapper.invocationSnapshot(key(invocationId), false);
            var attemptBytes = executionMapper.attemptSnapshot(key(attemptId), false);
            if (invocationBytes == null || attemptBytes == null) {
                return StoreOutcome.rejected(NOT_FOUND);
            }
            var invocation = executionRecordCodec.decode(invocationBytes, Invocation.class);
            var attempt = executionRecordCodec.decode(attemptBytes, Attempt.class);
            if (!owner.equals(ExecutionOwner.from(invocation.request().context()))) {
                return StoreOutcome.rejected(OWNER_MISMATCH);
            }
            if (!attempt.invocationId().equals(invocationId)) {
                return StoreOutcome.rejected(INVALID_STATE);
            }
            var encoded = bytes(result);
            ContractChecks.require(encoded.getBytes(StandardCharsets.UTF_8).length <= invocation.request().options().maxOutputBytes(),
                    "Result exceeds invocation byte limit");
            var digest = CanonicalJson.sha256(encoded);
            var reference = new ResultRef(id, type(result), 1, digest, partial(result));
            var existing = payloadMapper.result(id);
            if (existing != null) {
                integrity(existing);
                if (!existing.ownerKey().equals(ownerId) || !existing.invocationKey().equals(key(invocationId))
                        || !existing.attemptKey().equals(key(attemptId))) {
                    throw new IllegalStateException("Result metadata mismatch");
                }
                return reference.equals(reference(id, existing))
                        ? StoreOutcome.replayed(reference) : StoreOutcome.rejected(IDEMPOTENCY_CONFLICT);
            }
            payloadMapper.insertResult(id, ownerId, key(invocationId), key(attemptId), key(resultKey), reference.resultType(),
                    reference.partial() ? 1 : 0, digest, encoded);
            return StoreOutcome.applied(reference);
        });
    }

    @Override
    public Mono<InvocationResult> find(ExecutionOwner owner, String invocationId, ResultRef reference) {
        var ownerId = ownerKey(owner);
        ContractChecks.id(invocationId, "invocationId");
        Objects.requireNonNull(reference, "reference");
        return tx(() -> {
            var row = payloadMapper.result(reference.resultId());
            if (row == null || !row.ownerKey().equals(ownerId) || !row.invocationKey().equals(key(invocationId))) {
                return null;
            }
            integrity(row);
            if (!reference.equals(reference(reference.resultId(), row))) {
                throw new IllegalStateException("Result reference mismatch");
            }
            var value = executionRecordCodec.decode(row.snapshot(), InvocationResult.class);
            if (!type(value).equals(reference.resultType()) || partial(value) != reference.partial()) {
                throw new IllegalStateException("Result type or completeness mismatch");
            }
            return value;
        });
    }

    private static ResultRef reference(String id, PayloadRow row) {
        return new ResultRef(id, row.resultType(), row.schemaVersion(), row.payloadDigest(), row.partial() == 1);
    }

    private static String type(InvocationResult value) {
        return switch (value) {
            case InvocationResult.Generation ignored -> "model-result";
            case InvocationResult.Embedding ignored -> "embedding-result";
            case InvocationResult.Tool ignored -> "tool-result";
            case InvocationResult.Media ignored -> "media-result";
            case InvocationResult.RemoteApplication ignored -> "remote-application-result";
        };
    }

    private static boolean partial(InvocationResult value) {
        return switch (value) {
            case InvocationResult.Generation generation -> !generation.value().complete();
            case InvocationResult.Tool tool -> tool.value().outcome() != ToolResult.Outcome.SUCCEEDED;
            default -> false;
        };
    }
}
