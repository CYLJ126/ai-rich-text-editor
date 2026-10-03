package com.arte.app.ainew;

import com.arte.ai.model.budget.BudgetQuote;
import com.arte.ai.model.execution.ExecutionStatus;
import com.arte.ai.model.execution.ModelExecution;
import com.arte.ai.model.execution.ModelSubmission;
import com.arte.ai.model.execution.QueuedModelCall;
import com.arte.ai.model.generation.ModelResult;
import com.arte.ai.spi.store.ExecutionStore;
import com.arte.ai.spi.store.ModelWorkQueue;
import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.CancellationStatus;
import com.arte.base.model.execution.ExecutionError;
import com.arte.base.model.execution.ResultCertainty;
import com.arte.base.model.execution.SideEffectStatus;
import com.arte.base.model.identity.ExecutionScope;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * 单活数据库队列：数据库时钟、同事务受理、持久化取消、实例租约与每次认领的写入围栏。
 */
public final class JdbcModelWorkQueue implements ModelWorkQueue {
    public record Lease(ModelExecution execution, QueuedModelCall call, String owner, String token, Instant queuedAt) {
    }

    private record Job(String id, String key, String payload, Instant deadline, String owner, String token,
                       Instant until, boolean cancel, ExecutionStatus status, boolean dispatched, String digest,
                       Instant queuedAt) {
    }

    private record Authority(String owner, Instant until, boolean draining, Instant rateUntil, int starts) {
    }

    private static final String WORKER = "ai.model";
    private static final String JOBS = "SELECT w.*,x.status,x.dispatched,x.request_digest FROM arte_ai_new_work w JOIN arte_ai_new_execution x ON x.execution_id=w.execution_id ";
    private final JdbcTemplate jdbc;
    private final JdbcModelExecutionStore ledger;
    private final TransactionTemplate writes;
    private final String owner = UUID.randomUUID().toString();
    private final String tenant;
    private final int capacity, startsPerMinute;
    private final Duration leaseDuration;
    private boolean legacyChecked, initialized;
    private volatile boolean accepting;
    private volatile Runnable wakeup = () -> {
    };

    public JdbcModelWorkQueue(JdbcTemplate jdbc, PlatformTransactionManager manager, JdbcModelExecutionStore ledger,
                              String tenant, int threads, int queued, int startsPerMinute, Duration leaseDuration) {
        if (threads < 1 || queued < 0 || startsPerMinute < 1 || leaseDuration.compareTo(Duration.ofSeconds(2)) < 0)
            throw new IllegalArgumentException("invalid durable queue limits");
        this.jdbc = jdbc;
        this.ledger = ledger;
        this.tenant = tenant;
        this.capacity = Math.addExact(threads, queued);
        this.startsPerMinute = startsPerMinute;
        this.leaseDuration = leaseDuration;
        writes = new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    private Instant now() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Timestamp.class).toInstant();
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private Authority authority(boolean lock) {
        return jdbc.query("SELECT * FROM arte_ai_new_worker_lease WHERE worker_key=?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Authority(rs.getString("owner_id"), instant(rs.getTimestamp("lease_until")), rs.getBoolean("draining"),
                        instant(rs.getTimestamp("rate_until")), rs.getInt("starts_count")), WORKER).stream().findFirst().orElse(null);
    }

    private boolean owns(Authority authority, Instant now) {
        return authority != null && owner.equals(authority.owner()) && authority.until() != null && authority.until().isAfter(now);
    }

    private void requireOwner(boolean allowDraining) {
        if (!allowDraining && !accepting) throw failure(CommonErrorCode.BUSY, "worker-draining");
        var current = authority(true);
        if (!owns(current, now()) || !allowDraining && current.draining())
            throw failure(CommonErrorCode.BUSY, "worker-lease");
    }

    public boolean acquire() {
        if (!initialized) {
            try {
                jdbc.update("INSERT INTO arte_ai_new_worker_lease(worker_key) VALUES (?)", WORKER);
            } catch (DuplicateKeyException exists) { /* row is the serialization boundary */ }
            initialized = true;
        }
        accepting = Boolean.TRUE.equals(writes.execute(tx -> {
            var current = authority(true);
            var now = now();
            if (current.until() != null && current.until().isAfter(now) && !owner.equals(current.owner())) return false;
            jdbc.update("UPDATE arte_ai_new_worker_lease SET owner_id=?,lease_until=?,draining=FALSE WHERE worker_key=?", owner, Timestamp.from(now.plus(leaseDuration)), WORKER);
            return true;
        }));
        return accepting;
    }

    public boolean heartbeat() {
        return Boolean.TRUE.equals(writes.execute(tx -> {
            var current = authority(true);
            var now = now();
            if (!owns(current, now)) return false;
            var until = Timestamp.from(now.plus(leaseDuration));
            jdbc.update("UPDATE arte_ai_new_worker_lease SET lease_until=? WHERE worker_key=? AND owner_id=?", until, WORKER, owner);
            jdbc.update("UPDATE arte_ai_new_work SET lease_until=? WHERE worker_key=? AND owner_id=? AND lease_token IS NOT NULL", until, WORKER, owner);
            return true;
        }));
    }

    public void drain() {
        accepting = false;
        jdbc.update("UPDATE arte_ai_new_worker_lease SET draining=TRUE WHERE worker_key=? AND owner_id=?", WORKER, owner);
    }

    public void release() {
        accepting = false;
        jdbc.update("UPDATE arte_ai_new_worker_lease SET lease_until=CURRENT_TIMESTAMP,draining=TRUE WHERE worker_key=? AND owner_id=?", WORKER, owner);
    }

    @Override
    public ExecutionStore.Acceptance accept(ModelSubmission submission, BudgetQuote quote, QueuedModelCall call) {
        if (!tenant.equals(submission.context().scope().tenantId()) || !submission.context().equals(call.request().context())
                || !submission.requestDigest().equals(call.fingerprint()))
            throw failure(CommonErrorCode.INVALID_ARGUMENT, "work-context");
        var payload = ModelWorkJson.encode(call);
        if (payload.getBytes(StandardCharsets.UTF_8).length > 1048576)
            throw failure(CommonErrorCode.INVALID_ARGUMENT, "work-size");
        var accepted = ledger.acceptAtomic(submission, quote, () -> requireOwner(false), () -> {
            if (!call.executeBy().isAfter(now())) throw failure(CommonErrorCode.DEADLINE_EXCEEDED, "queue");
            int active = jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_work w JOIN arte_ai_new_execution x ON x.execution_id=w.execution_id WHERE w.worker_key=? AND w.finished=FALSE AND x.status IN ('ACCEPTED','RUNNING')", Integer.class, WORKER);
            if (active >= capacity) throw failure(CommonErrorCode.BUSY, "queue-capacity");
        }, execution -> jdbc.update("INSERT INTO arte_ai_new_work(execution_id,scope_key,worker_key,work_json,queued_at,deadline_at) VALUES (?,?,?,?,CURRENT_TIMESTAMP,?)",
                execution.executionId(), ModelKeys.scope(execution.scope()), WORKER, payload, Timestamp.from(call.executeBy().truncatedTo(java.time.temporal.ChronoUnit.MICROS))));
        // 唤醒只是延迟优化；提交后通知失败不应把已经可靠受理的请求报告为失败。
        if (accepted.created()) {
            try {
                wakeup.run();
            } catch (RuntimeException ignored) { /* periodic poll will dispatch */ }
        }
        return accepted;
    }

    public void onAvailable(Runnable wakeup) {
        this.wakeup = Objects.requireNonNull(wakeup);
    }

    public Optional<com.arte.base.model.execution.ExecutionContext> candidateContext() {
        var candidates = jobs("WHERE w.worker_key=? AND w.finished=FALSE AND x.status='ACCEPTED' AND w.owner_id IS NULL AND w.cancel_requested=FALSE AND w.deadline_at>CURRENT_TIMESTAMP ORDER BY w.queued_at,w.execution_id LIMIT 1", WORKER);
        if (candidates.isEmpty()) return Optional.empty();
        try {
            return Optional.of(decode(candidates.getFirst()).workerContext());
        } catch (RuntimeException invalid) {
            var job = candidates.getFirst();
            ledger.finishKey(job.key(), job.id(), ExecutionStatus.FAILED, null, error(CommonErrorCode.INVALID_ARGUMENT, "work-format", false), () -> unclaimed(job.id()));
            clearTerminal(job.id());
            return Optional.empty();
        }
    }

    public boolean hasReady() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_work w JOIN arte_ai_new_execution x ON x.execution_id=w.execution_id WHERE w.worker_key=? AND w.finished=FALSE AND x.status='ACCEPTED' AND w.owner_id IS NULL AND w.cancel_requested=FALSE AND w.deadline_at>CURRENT_TIMESTAMP", Integer.class, WORKER) > 0;
    }

    public Optional<Lease> claim() {
        return writes.execute(tx -> {
            requireOwner(false);
            var now = now();
            var current = authority(false);
            if (current.rateUntil() != null && current.rateUntil().isAfter(now) && current.starts() >= startsPerMinute)
                return Optional.empty();
            var jobs = jobs("WHERE w.worker_key=? AND w.finished=FALSE AND x.status='ACCEPTED' AND w.owner_id IS NULL AND w.cancel_requested=FALSE AND w.deadline_at>? ORDER BY w.queued_at,w.execution_id LIMIT 1", WORKER, Timestamp.from(now));
            if (jobs.isEmpty()) return Optional.empty();
            var job = jobs.getFirst();
            QueuedModelCall call;
            try {
                call = decode(job);
            } catch (RuntimeException invalid) {
                ledger.finishKey(job.key(), job.id(), ExecutionStatus.FAILED, null, error(CommonErrorCode.INVALID_ARGUMENT, "work-format", false), () -> unclaimed(job.id()));
                return Optional.empty();
            }
            jdbc.queryForList("SELECT execution_id FROM arte_ai_new_execution WHERE execution_id=? FOR UPDATE", job.id());
            if (!unclaimed(job.id())) return Optional.empty();
            String token = UUID.randomUUID().toString();
            jdbc.update("UPDATE arte_ai_new_work SET owner_id=?,lease_token=?,lease_until=? WHERE execution_id=?", owner, token, Timestamp.from(now.plus(leaseDuration)), job.id());
            boolean sameWindow = current.rateUntil() != null && current.rateUntil().isAfter(now);
            jdbc.update("UPDATE arte_ai_new_worker_lease SET starts_count=?,rate_until=? WHERE worker_key=?",
                    sameWindow ? current.starts() + 1 : 1, Timestamp.from(sameWindow ? current.rateUntil() : now.plusSeconds(60)), WORKER);
            var execution = ledger.find(call.request().context().scope(), job.id()).orElseThrow();
            if (!execution.capabilityRef().equals(call.request().capabilityRef()) || !execution.bindingRef().equals(call.request().bindingRef()))
                throw failure(CommonErrorCode.VERSION_CONFLICT, "work-binding");
            return Optional.of(new Lease(execution, call, owner, token, job.queuedAt()));
        });
    }

    private QueuedModelCall decode(Job job) {
        var call = ModelWorkJson.decode(job.payload());
        if (!tenant.equals(call.request().context().scope().tenantId()) || !ModelKeys.scope(call.request().context().scope()).equals(job.key())
                || !job.digest().equals(call.fingerprint()) || !job.deadline().equals(call.executeBy().truncatedTo(java.time.temporal.ChronoUnit.MICROS)))
            throw new IllegalArgumentException("invalid queued identity");
        var execution = ledger.find(call.request().context().scope(), job.id()).orElseThrow();
        if (!execution.capabilityRef().equals(call.request().capabilityRef()) || !execution.bindingRef().equals(call.request().bindingRef()))
            throw new IllegalArgumentException("invalid queued binding");
        return call;
    }

    private boolean unclaimed(String id) {
        var row = jobs("WHERE w.execution_id=?", id).getFirst();
        return row.status() == ExecutionStatus.ACCEPTED && !row.dispatched() && row.owner() == null;
    }

    public ExecutionStore fenced(Lease lease) {
        return new ExecutionStore() {
            public Acceptance accept(ModelSubmission submission, BudgetQuote quote) {
                throw new UnsupportedOperationException();
            }

            public Optional<ModelExecution> findIdempotent(ExecutionScope scope, String key, String digest) {
                return ledger.findIdempotent(scope, key, digest);
            }

            public Optional<ModelExecution> findIdempotent(ExecutionScope scope, String key) {
                return ledger.findIdempotent(scope, key);
            }

            public Optional<ModelExecution> find(ExecutionScope scope, String id) {
                return ledger.find(scope, id);
            }

            public boolean start(ExecutionScope scope, String id) {
                requireIdentity(lease, scope, id);
                return ledger.startGuarded(scope, id, () -> valid(lease, true, true));
            }

            public void markDispatched(ExecutionScope scope, String id) {
                requireIdentity(lease, scope, id);
                ledger.dispatchGuarded(scope, id, () -> valid(lease, true, false));
            }

            public void finish(ExecutionScope scope, String id, ExecutionStatus status, ModelResult result, ExecutionError error) {
                requireIdentity(lease, scope, id);
                ledger.finishKey(ModelKeys.scope(scope), id, status, result, error, () -> {
                    if (!valid(lease, false, false)) throw failure(CommonErrorCode.INTERRUPTED, "work-fence");
                    return true;
                });
                cleanup(lease);
            }

            public void appendDelta(ExecutionScope scope, String id, String text) {
                requireIdentity(lease, scope, id);
                ledger.appendDeltaGuarded(scope, id, text, () -> valid(lease, false, false));
            }

        };
    }

    private static void requireIdentity(Lease lease, ExecutionScope scope, String id) {
        if (!lease.execution().scope().equals(scope) || !lease.execution().executionId().equals(id))
            throw failure(CommonErrorCode.NOT_FOUND, "work-fence");
    }

    private boolean valid(Lease lease, boolean checkStop, boolean checkDrain) {
        jdbc.queryForList("SELECT execution_id FROM arte_ai_new_work WHERE execution_id=? FOR UPDATE", lease.execution().executionId());
        var job = jobs("WHERE w.execution_id=?", lease.execution().executionId()).getFirst();
        var now = now();
        var current = authority(false);
        return lease.owner().equals(job.owner()) && lease.token().equals(job.token()) && job.until() != null && job.until().isAfter(now)
                && owns(current, now) && (!checkDrain || !current.draining()) && (!checkStop || !job.cancel() && job.deadline().isAfter(now));
    }

    private void cleanup(Lease lease) {
        jdbc.update("UPDATE arte_ai_new_work SET owner_id=NULL,lease_token=NULL,lease_until=NULL,finished=TRUE WHERE execution_id=? AND lease_token=? AND EXISTS (SELECT 1 FROM arte_ai_new_execution x WHERE x.execution_id=? AND x.status NOT IN ('ACCEPTED','RUNNING'))",
                lease.execution().executionId(), lease.token(), lease.execution().executionId());
    }

    public boolean cancelled(Lease lease) {
        return jdbc.queryForObject("SELECT cancel_requested FROM arte_ai_new_work WHERE execution_id=?", Boolean.class, lease.execution().executionId());
    }

    @Override
    public CancellationStatus requestCancellation(ExecutionScope scope, String id) {
        var execution = ledger.find(scope, id).orElseThrow(() -> failure(CommonErrorCode.NOT_FOUND, "cancel"));
        if (execution.status() != ExecutionStatus.ACCEPTED && execution.status() != ExecutionStatus.RUNNING)
            return CancellationStatus.ALREADY_COMPLETED;
        if (jdbc.update("UPDATE arte_ai_new_work SET cancel_requested=TRUE WHERE scope_key=? AND execution_id=?", ModelKeys.scope(scope), id) == 0)
            return CancellationStatus.UNCONFIRMED;
        ledger.finishKey(ModelKeys.scope(scope), id, ExecutionStatus.CANCELLED, null, error(CommonErrorCode.INTERRUPTED, "queue-cancel", false), () -> {
            var row = jobs("WHERE w.execution_id=?", id).getFirst();
            return row.status() == ExecutionStatus.ACCEPTED && !row.dispatched() && row.cancel();
        });
        clearTerminal(id);
        var status = ledger.find(scope, id).orElseThrow().status();
        return status == ExecutionStatus.CANCELLED ? CancellationStatus.CANCELLED : CancellationStatus.CANCELLING;
    }

    /**
     * 超期和失联恢复不重发已派发任务；未派发且未超期的工作撤销旧租约后重新排队。
     */
    public void recover() {
        requireOwnerOutside();
        var now = now();
        jdbc.update("UPDATE arte_ai_new_work SET finished=TRUE,owner_id=NULL,lease_token=NULL,lease_until=NULL WHERE worker_key=? AND finished=FALSE AND EXISTS (SELECT 1 FROM arte_ai_new_execution x WHERE x.execution_id=arte_ai_new_work.execution_id AND x.status NOT IN ('ACCEPTED','RUNNING'))", WORKER);
        for (var job : jobs("WHERE w.worker_key=? AND w.finished=FALSE AND x.status IN ('ACCEPTED','RUNNING') ORDER BY w.queued_at LIMIT 100", WORKER)) {
            boolean abandoned = job.owner() == null || !owner.equals(job.owner()) || job.until() == null || !job.until().isAfter(now);
            if (!abandoned) continue;
            if (job.dispatched() || job.cancel() || !job.deadline().isAfter(now)) {
                var code = job.dispatched() ? CommonErrorCode.OUTCOME_UNKNOWN : job.cancel() ? CommonErrorCode.INTERRUPTED : CommonErrorCode.DEADLINE_EXCEEDED;
                var status = job.dispatched() ? ExecutionStatus.OUTCOME_UNKNOWN : job.cancel() ? ExecutionStatus.CANCELLED : ExecutionStatus.TIMED_OUT;
                ledger.finishKey(job.key(), job.id(), status, null, error(code, "work-recovery", job.dispatched()), () -> abandoned(job, job.dispatched()));
                clearTerminal(job.id());
            } else if (job.owner() != null || job.status() == ExecutionStatus.RUNNING) {
                writes.executeWithoutResult(tx -> {
                    jdbc.queryForList("SELECT execution_id FROM arte_ai_new_execution WHERE execution_id=? FOR UPDATE", job.id());
                    if (!abandoned(job, false)) return;
                    if (job.status() == ExecutionStatus.RUNNING) ledger.recoveredBeforeDispatch(job.key(), job.id());
                    jdbc.update("UPDATE arte_ai_new_work SET owner_id=NULL,lease_token=NULL,lease_until=NULL WHERE execution_id=?", job.id());
                });
            }
        }
        // 升级前遗留的内存任务没有恢复正文：未派发中断并退款；已派发保留未知结果及预算。
        var legacyRows = legacyChecked ? List.<Map<String, Object>>of() : jdbc.queryForList("SELECT x.execution_id,x.scope_key,x.dispatched FROM arte_ai_new_execution x WHERE x.status IN ('ACCEPTED','RUNNING') AND NOT EXISTS (SELECT 1 FROM arte_ai_new_work w WHERE w.execution_id=x.execution_id) LIMIT 100");
        legacyChecked = legacyRows.isEmpty();
        for (var legacy : legacyRows) {
            String id = (String) legacy.get("execution_id"), key = (String) legacy.get("scope_key");
            boolean sent = Boolean.TRUE.equals(legacy.get("dispatched"));
            ledger.finishKey(key, id, sent ? ExecutionStatus.OUTCOME_UNKNOWN : ExecutionStatus.INTERRUPTED, null,
                    error(sent ? CommonErrorCode.OUTCOME_UNKNOWN : CommonErrorCode.INTERRUPTED, "legacy-recovery", sent), () -> {
                        requireOwnerOutside();
                        return jdbc.queryForObject("SELECT dispatched FROM arte_ai_new_execution WHERE execution_id=?", Boolean.class, id) == sent;
                    });
        }
    }

    private void requireOwnerOutside() {
        if (!owns(authority(false), now())) throw failure(CommonErrorCode.BUSY, "worker-lease");
    }

    private boolean abandoned(Job expected, boolean sent) {
        requireOwnerOutside();
        var row = jobs("WHERE w.execution_id=?", expected.id()).getFirst();
        var now = now();
        return row.dispatched() == sent && (row.status() == ExecutionStatus.ACCEPTED || row.status() == ExecutionStatus.RUNNING)
                && (row.owner() == null || !owner.equals(row.owner()) || row.until() == null || !row.until().isAfter(now));
    }

    private void clearTerminal(String id) {
        jdbc.update("UPDATE arte_ai_new_work SET owner_id=NULL,lease_token=NULL,lease_until=NULL,finished=TRUE WHERE execution_id=? AND EXISTS (SELECT 1 FROM arte_ai_new_execution x WHERE x.execution_id=? AND x.status NOT IN ('ACCEPTED','RUNNING'))", id, id);
    }

    private List<Job> jobs(String where, Object... args) {
        return jdbc.query(JOBS + where, (rs, row) -> new Job(rs.getString("execution_id"), rs.getString("scope_key"), rs.getString("work_json"), instant(rs.getTimestamp("deadline_at")),
                rs.getString("owner_id"), rs.getString("lease_token"), instant(rs.getTimestamp("lease_until")), rs.getBoolean("cancel_requested"), ExecutionStatus.valueOf(rs.getString("status")),
                rs.getBoolean("dispatched"), rs.getString("request_digest"), instant(rs.getTimestamp("queued_at"))), args);
    }

    private static ExecutionError error(CommonErrorCode code, String stage, boolean sent) {
        return ExecutionError.of(code, stage, false, sent ? SideEffectStatus.UNKNOWN : SideEffectStatus.NONE, sent ? ResultCertainty.UNKNOWN : ResultCertainty.CONFIRMED, null);
    }

    private static BaseException failure(CommonErrorCode code, String stage) {
        return new BaseException(error(code, stage, false));
    }
}
