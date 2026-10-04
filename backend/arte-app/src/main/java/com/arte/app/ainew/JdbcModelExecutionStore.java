package com.arte.app.ainew;

import com.arte.ai.model.budget.BudgetQuote;
import com.arte.ai.model.budget.BudgetReservation;
import com.arte.ai.model.budget.BudgetStatus;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.model.execution.ExecutionStatus;
import com.arte.ai.model.execution.ModelEvent;
import com.arte.ai.model.execution.ModelExecution;
import com.arte.ai.model.execution.ModelSubmission;
import com.arte.ai.model.generation.ModelResult;
import com.arte.ai.spi.store.BudgetLedger;
import com.arte.ai.spi.store.ExecutionEventStore;
import com.arte.ai.spi.store.ExecutionStore;
import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionError;
import com.arte.base.model.execution.ExecutionEvent;
import com.arte.base.model.execution.ResultCertainty;
import com.arte.base.model.execution.SideEffectStatus;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.validation.ContractChecks;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.*;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 独立事务的单次模型账本：受理+预算及终态+结果+事件+结算各自原子提交。
 */
public final class JdbcModelExecutionStore implements ExecutionStore, ExecutionEventStore, BudgetLedger {
    private static final String EXECUTIONS = "SELECT x.*,CASE WHEN x.status='SUCCEEDED' THEN '' ELSE COALESCE(o.partial_text,'') END AS partial_text,COALESCE(o.last_sequence,-1) AS partial_sequence FROM arte_ai_new_execution x LEFT JOIN arte_ai_new_stream_output o ON o.execution_id=x.execution_id ";
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writes;
    private final Clock clock;
    private final java.util.Set<java.util.function.Consumer<String>> listeners = new java.util.concurrent.CopyOnWriteArraySet<>();

    public AutoCloseable watchEvents(java.util.function.Consumer<String> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void signal(String id) {
        for (var listener : listeners) {
            try {
                listener.accept(id);
            } catch (RuntimeException ignored) { /* durable polling remains available */ }
        }
    }


    public JdbcModelExecutionStore(JdbcTemplate jdbc, PlatformTransactionManager manager, Clock clock) {
        this.jdbc = ContractChecks.required(jdbc, "jdbc");
        jdbc.queryForList("SELECT partial_text,last_sequence FROM arte_ai_new_stream_output WHERE 1=0");
        jdbc.queryForList("SELECT text_delta FROM arte_ai_new_stream_delta WHERE 1=0");
        jdbc.queryForList("SELECT resource_context_json FROM arte_ai_new_execution WHERE 1=0");
        this.clock = ContractChecks.required(clock, "clock");
        writes = new TransactionTemplate(ContractChecks.required(manager, "transactionManager"));
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * 预算开通使用此稳定作用域键；不接受客户端自报键作为授权。
     */
    public static String budgetKey(ExecutionScope scope) {
        return ModelKeys.scope(scope);
    }

    @Override
    public Acceptance accept(ModelSubmission submission, BudgetQuote quote) {
        return acceptAtomic(submission, quote, () -> {
        }, () -> {
        }, execution -> {
        });
    }

    Acceptance acceptAtomic(ModelSubmission submission, BudgetQuote quote, Runnable lockPartition,
                            Runnable checkCapacity, Consumer<ModelExecution> enqueue) {
        if (submission.idempotencyKey().length() > 128)
            throw new IllegalArgumentException("idempotencyKey is too long");
        var scope = submission.context().scope();
        String scopeKey = ModelKeys.scope(scope);
        return writes.execute(tx -> {
            lockPartition.run();
            // 固定主体预算行作为受理互斥边界；缺少预算不自动生成默认额度。
            var accounts = jdbc.query("SELECT amount_limit, reserved_amount, spent_amount, currency, enabled FROM arte_ai_new_budget WHERE scope_key = ? FOR UPDATE",
                    (rs, row) -> new BudgetAccount(rs.getBigDecimal("amount_limit"), rs.getBigDecimal("reserved_amount"), rs.getBigDecimal("spent_amount"), rs.getString("currency"), rs.getBoolean("enabled")), scopeKey);
            if (accounts.isEmpty()) throw error(CommonErrorCode.POLICY_UNAVAILABLE, "budget");
            var account = accounts.getFirst();
            String identity = ModelKeys.hash(scopeKey, "model.generate", submission.idempotencyKey());
            var old = jdbc.query(EXECUTIONS + "WHERE x.identity_key = ? AND x.scope_key = ?", (rs, row) -> {
                if (!submission.requestDigest().equals(rs.getString("request_digest")))
                    throw error(CommonErrorCode.IDEMPOTENCY_CONFLICT, "accept");
                return map(rs, scope);
            }, identity, scopeKey);
            if (!old.isEmpty()) return new Acceptance(old.getFirst(), false);
            checkCapacity.run();
            if (!account.enabled() || !quote.currency().equals(account.currency()))
                throw error(CommonErrorCode.POLICY_UNAVAILABLE, "budget");
            BigDecimal limit = account.limit(), reserved = account.reserved(), spent = account.spent();
            if (limit.subtract(reserved).subtract(spent).compareTo(quote.maximumAmount()) < 0)
                throw error(CommonErrorCode.RATE_LIMITED, "budget");
            var plan = submission.plan();
            var now = Timestamp.from(clock.instant());
            jdbc.update("UPDATE arte_ai_new_budget SET reserved_amount = reserved_amount + ?, revision = revision + 1 WHERE scope_key = ?", quote.maximumAmount(), scopeKey);
            jdbc.update("INSERT INTO arte_ai_new_execution (execution_id, attempt_id, scope_key, identity_key, request_digest, capability_id, capability_version, binding_id, binding_version, connection_id, connection_version, status, revision, dispatched, accepted_at, reserved_amount, currency, budget_status, resource_context_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'ACCEPTED', 1, FALSE, ?, ?, ?, 'RESERVED', ?)",
                    submission.executionId(), submission.attemptId(), scopeKey, identity, submission.requestDigest(), plan.capability().descriptor().ref().definitionId(), plan.capability().descriptor().ref().version(),
                    plan.binding().ref().definitionId(), plan.binding().ref().version(), plan.connection().ref().definitionId(), plan.connection().ref().version(), now, quote.maximumAmount(), quote.currency(), ResourceContextJson.encode(submission.resourceContext()));
            event(submission.executionId(), submission.attemptId(), 0, ExecutionStatus.ACCEPTED, null, null);
            var execution = find(scope, submission.executionId()).orElseThrow();
            enqueue.accept(execution);
            return new Acceptance(execution, true);
        });
    }

    @Override
    public Optional<ModelExecution> findIdempotent(ExecutionScope scope, String key, String digest) {
        String scopeKey = ModelKeys.scope(scope);
        var rows = jdbc.query(EXECUTIONS + "WHERE x.scope_key = ? AND x.identity_key = ?", (rs, row) -> {
            if (!digest.equals(rs.getString("request_digest")))
                throw error(CommonErrorCode.IDEMPOTENCY_CONFLICT, "accept");
            return map(rs, scope);
        }, scopeKey, ModelKeys.hash(scopeKey, "model.generate", key));
        return rows.stream().findFirst();
    }

    @Override
    public Optional<ModelExecution> findIdempotent(ExecutionScope scope, String key) {
        String scopeKey = ModelKeys.scope(scope);
        return jdbc.query(EXECUTIONS + "WHERE x.scope_key = ? AND x.identity_key = ?",
                (rs, row) -> map(rs, scope), scopeKey, ModelKeys.hash(scopeKey, "model.generate", key)).stream().findFirst();
    }

    @Override
    public Optional<ModelExecution> find(ExecutionScope scope, String id) {
        var rows = jdbc.query(EXECUTIONS + "WHERE x.scope_key = ? AND x.execution_id = ?", (rs, row) -> map(rs, scope), ModelKeys.scope(scope), id);
        return rows.stream().findFirst();
    }

    @Override
    public List<ModelExecution> findAll(ExecutionScope scope, List<String> ids) {
        if (ids.size() > 256) throw new IllegalArgumentException("execution batch exceeds limit");
        var distinct = ids.stream().distinct().toList();
        if (distinct.isEmpty()) return List.of();
        var args = new ArrayList<Object>();
        args.add(ModelKeys.scope(scope));
        args.addAll(distinct);
        return jdbc.query(EXECUTIONS + "WHERE x.scope_key=? AND x.execution_id IN ("
                        + String.join(",", Collections.nCopies(distinct.size(), "?")) + ")",
                (rs, row) -> map(rs, scope), args.toArray());
    }

    @Override
    public boolean start(ExecutionScope scope, String id) {
        return startGuarded(scope, id, () -> true);
    }

    boolean startGuarded(ExecutionScope scope, String id, BooleanSupplier guard) {
        boolean started = Boolean.TRUE.equals(writes.execute(tx -> {
            jdbc.queryForList("SELECT execution_id FROM arte_ai_new_execution WHERE scope_key=? AND execution_id=? FOR UPDATE", ModelKeys.scope(scope), id);
            if (!guard.getAsBoolean()) return false;
            int updated = jdbc.update("UPDATE arte_ai_new_execution SET status = 'RUNNING', revision = revision + 1 WHERE scope_key = ? AND execution_id = ? AND status = 'ACCEPTED'", ModelKeys.scope(scope), id);
            if (updated != 1) return false;
            var execution = find(scope, id).orElseThrow();
            event(id, execution.attemptId(), nextSequence(id), ExecutionStatus.RUNNING, null, null);
            return true;
        }));
        if (started) signal(id);
        return started;
    }

    @Override
    public void markDispatched(ExecutionScope scope, String id) {
        dispatchGuarded(scope, id, () -> true);
    }

    void dispatchGuarded(ExecutionScope scope, String id, BooleanSupplier guard) {
        writes.executeWithoutResult(tx -> {
            jdbc.queryForList("SELECT execution_id FROM arte_ai_new_execution WHERE scope_key=? AND execution_id=? FOR UPDATE", ModelKeys.scope(scope), id);
            if (!guard.getAsBoolean()) throw error(CommonErrorCode.INTERRUPTED, "work-fence");
            if (jdbc.update("UPDATE arte_ai_new_execution SET dispatched = TRUE, revision = revision + 1 WHERE scope_key = ? AND execution_id = ? AND status = 'RUNNING' AND dispatched = FALSE", ModelKeys.scope(scope), id) != 1)
                throw error(CommonErrorCode.VERSION_CONFLICT, "dispatch");
        });
    }

    @Override
    public void finish(ExecutionScope scope, String id, ExecutionStatus status, ModelResult result, ExecutionError error) {
        finishKey(ModelKeys.scope(scope), id, status, result, error, () -> true);
    }

    void finishKey(String key, String id, ExecutionStatus status, ModelResult result, ExecutionError error, BooleanSupplier guard) {
        if (status == ExecutionStatus.ACCEPTED || status == ExecutionStatus.RUNNING)
            throw new IllegalArgumentException("terminal status is required");
        String encodedResult = ModelJson.result(result), encodedError = ModelJson.error(error);
        if (encodedResult != null && encodedResult.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 1048576)
            throw new IllegalArgumentException("result is too large");
        writes.executeWithoutResult(tx -> {
            // 所有涉及两个表的操作按预算→执行顺序加锁，避免同主体结算／受理死锁。
            jdbc.queryForList("SELECT scope_key FROM arte_ai_new_budget WHERE scope_key = ? FOR UPDATE", key);
            var rows = jdbc.query("SELECT * FROM arte_ai_new_execution WHERE scope_key = ? AND execution_id = ? FOR UPDATE",
                    (rs, row) -> new Settlement(rs.getString("attempt_id"), ExecutionStatus.valueOf(rs.getString("status")),
                            rs.getBoolean("dispatched"), rs.getBigDecimal("reserved_amount"), rs.getString("currency")), key, id);
            if (rows.isEmpty()) throw error(CommonErrorCode.NOT_FOUND, "finish");
            var row = rows.getFirst();
            if (!guard.getAsBoolean()) return;
            String previous = row.status().name();
            if (!previous.equals("ACCEPTED") && !previous.equals("RUNNING")) return;
            boolean dispatched = row.dispatched();
            if (status == ExecutionStatus.SUCCEEDED && (!dispatched || result == null || error != null)
                    || status != ExecutionStatus.SUCCEEDED && (result != null || error == null))
                throw new IllegalArgumentException("terminal result is inconsistent");
            BigDecimal reserved = row.reserved();
            String currency = row.currency();
            BudgetStatus budgetStatus;
            if (!dispatched) {
                budgetStatus = BudgetStatus.RELEASED;
                jdbc.update("UPDATE arte_ai_new_budget SET reserved_amount = reserved_amount - ?, revision = revision + 1 WHERE scope_key = ?", reserved, key);
            } else if (result != null && result.usage().reportedCost() != null && currency.equals(result.usage().currency())) {
                budgetStatus = BudgetStatus.SETTLED;
                BigDecimal cost = result.usage().reportedCost();
                if (cost.scale() > 8 || cost.precision() > 24)
                    throw new IllegalArgumentException("cost exceeds ledger precision");
                jdbc.update("UPDATE arte_ai_new_budget SET reserved_amount = reserved_amount - ?, spent_amount = spent_amount + ?, revision = revision + 1 WHERE scope_key = ?", reserved, cost, key);
            } else budgetStatus = BudgetStatus.PENDING_RECONCILIATION;
            jdbc.update("UPDATE arte_ai_new_execution SET status = ?, revision = revision + 1, result_json = ?, error_json = ?, budget_status = ? WHERE scope_key = ? AND execution_id = ?",
                    status.name(), encodedResult, encodedError, budgetStatus.name(), key, id);
            event(id, row.attemptId(), nextSequence(id), status, encodedResult, encodedError);
        });
        signal(id);
    }

    @Override
    public void appendDelta(ExecutionScope scope, String id, String text) {
        appendDeltaGuarded(scope, id, text, () -> true);
    }

    void appendDeltaGuarded(ExecutionScope scope, String id, String text, BooleanSupplier guard) {
        int bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if (bytes < 1 || bytes > 16384) throw new IllegalArgumentException("invalid delta size");
        writes.executeWithoutResult(tx -> {
            var rows = jdbc.queryForList("SELECT attempt_id,status,dispatched FROM arte_ai_new_execution WHERE scope_key=? AND execution_id=? FOR UPDATE", ModelKeys.scope(scope), id);
            if (rows.isEmpty()) throw error(CommonErrorCode.NOT_FOUND, "stream-write");
            if (!guard.getAsBoolean()) throw error(CommonErrorCode.INTERRUPTED, "work-fence");
            var execution = rows.getFirst();
            if (!"RUNNING".equals(execution.get("status")) || !Boolean.TRUE.equals(execution.get("dispatched")))
                throw error(CommonErrorCode.VERSION_CONFLICT, "stream-write");
            var sizes = jdbc.queryForList("SELECT utf8_bytes FROM arte_ai_new_stream_output WHERE execution_id=?", id);
            int previous = sizes.isEmpty() ? 0 : ((Number) sizes.getFirst().get("utf8_bytes")).intValue();
            if (bytes > 1048576 - previous) throw error(CommonErrorCode.INVALID_ARGUMENT, "stream-capacity");
            long sequence = nextSequence(id);
            event(id, (String) execution.get("attempt_id"), sequence, ExecutionStatus.RUNNING, null, null);
            jdbc.update("INSERT INTO arte_ai_new_stream_delta(execution_id,sequence_no,text_delta) VALUES (?,?,?)", id, sequence, text);
            if (sizes.isEmpty())
                jdbc.update("INSERT INTO arte_ai_new_stream_output(execution_id,partial_text,utf8_bytes,last_sequence) VALUES (?,?,?,?)", id, text, bytes, sequence);
            else
                jdbc.update("UPDATE arte_ai_new_stream_output SET partial_text=CONCAT(partial_text,?),utf8_bytes=utf8_bytes+?,last_sequence=? WHERE execution_id=?", text, bytes, sequence, id);
        });
        signal(id);
    }

    @Override
    public BudgetReservation reservation(ExecutionScope scope, String id) {
        return jdbc.queryForObject("SELECT reserved_amount, currency, budget_status FROM arte_ai_new_execution WHERE scope_key = ? AND execution_id = ?", (rs, row) ->
                new BudgetReservation(id, ResourceRef.saved("ai-budget", ModelKeys.scope(scope), "1"), id, rs.getBigDecimal("reserved_amount"), rs.getString("currency"), BudgetStatus.valueOf(rs.getString("budget_status"))), ModelKeys.scope(scope), id);
    }

    @Override
    public List<ExecutionEvent<ModelEvent>> read(ExecutionScope scope, String id, long after, int limit) {
        if (after < -1 || limit <= 0 || limit > 100)
            throw new IllegalArgumentException("invalid event cursor or page size");
        if (find(scope, id).isEmpty()) throw error(CommonErrorCode.NOT_FOUND, "events");
        return jdbc.query("SELECT e.*,d.text_delta FROM arte_ai_new_event e JOIN arte_ai_new_execution x ON x.execution_id = e.execution_id LEFT JOIN arte_ai_new_stream_delta d ON d.execution_id=e.execution_id AND d.sequence_no=e.sequence_no WHERE x.scope_key = ? AND e.execution_id = ? AND e.sequence_no > ? ORDER BY e.sequence_no LIMIT ?", (rs, row) ->
                new ExecutionEvent<>(id, rs.getString("attempt_id"), rs.getLong("sequence_no"), "ai.model." + rs.getString("status").toLowerCase(Locale.ROOT), rs.getTimestamp("occurred_at").toInstant(),
                        new ModelEvent(ExecutionStatus.valueOf(rs.getString("status")), ModelJson.result(rs.getString("result_json")), ModelJson.error(rs.getString("error_json")), rs.getString("text_delta")), null), ModelKeys.scope(scope), id, after, limit);
    }

    /**
     * 运维恢复入口：仅在已确认原实例／工作退出后调用，不重新派发。尚未提供公开网络接口。
     */
    public void interruptAbandoned(ExecutionScope scope, String id) {
        var execution = find(scope, id).orElseThrow(() -> error(CommonErrorCode.NOT_FOUND, "recovery"));
        if (execution.status() != ExecutionStatus.ACCEPTED && execution.status() != ExecutionStatus.RUNNING) return;
        boolean sent = execution.dispatched();
        finish(scope, id, sent ? ExecutionStatus.OUTCOME_UNKNOWN : ExecutionStatus.INTERRUPTED, null,
                ExecutionError.of(sent ? CommonErrorCode.OUTCOME_UNKNOWN : CommonErrorCode.INTERRUPTED, "recovery", false,
                        sent ? SideEffectStatus.UNKNOWN : SideEffectStatus.NONE, sent ? ResultCertainty.UNKNOWN : ResultCertainty.CONFIRMED, null));
    }

    long nextSequence(String id) {
        return jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no),-1)+1 FROM arte_ai_new_event WHERE execution_id=?", Long.class, id);
    }

    void recoveredBeforeDispatch(String key, String id) {
        // Caller locks the execution row and verifies the expired work lease first.
        if (jdbc.update("UPDATE arte_ai_new_execution SET status='ACCEPTED',revision=revision+1 WHERE scope_key=? AND execution_id=? AND status='RUNNING' AND dispatched=FALSE", key, id) == 0)
            return;
        String attempt = jdbc.queryForObject("SELECT attempt_id FROM arte_ai_new_execution WHERE execution_id=?", String.class, id);
        event(id, attempt, nextSequence(id), ExecutionStatus.ACCEPTED, null, null);
    }

    private void event(String id, String attempt, long sequence, ExecutionStatus status, String result, String error) {
        jdbc.update("INSERT INTO arte_ai_new_event (execution_id, attempt_id, sequence_no, status, occurred_at, result_json, error_json) VALUES (?, ?, ?, ?, ?, ?, ?)",
                id, attempt, sequence, status.name(), Timestamp.from(clock.instant()), result, error);
    }

    private ModelExecution map(ResultSet rs, ExecutionScope scope) throws SQLException {
        return new ModelExecution(rs.getString("execution_id"), rs.getString("attempt_id"), scope, new DefinitionRef("ai-capability", rs.getString("capability_id"), rs.getString("capability_version")),
                new DefinitionRef("ai-binding", rs.getString("binding_id"), rs.getString("binding_version")), new DefinitionRef("ai-connection", rs.getString("connection_id"), rs.getString("connection_version")),
                ExecutionStatus.valueOf(rs.getString("status")), rs.getLong("revision"), rs.getBoolean("dispatched"), rs.getTimestamp("accepted_at").toInstant(), ModelJson.result(rs.getString("result_json")), ModelJson.error(rs.getString("error_json")), rs.getString("partial_text"), rs.getLong("partial_sequence"), ResourceContextJson.decode(rs.getString("resource_context_json")));
    }

    private record BudgetAccount(BigDecimal limit, BigDecimal reserved, BigDecimal spent, String currency,
                                 boolean enabled) {
    }

    private record Settlement(String attemptId, ExecutionStatus status, boolean dispatched, BigDecimal reserved,
                              String currency) {
    }

    private static BaseException error(CommonErrorCode code, String stage) {
        return new BaseException(ExecutionError.of(code, stage, false, SideEffectStatus.NONE, ResultCertainty.CONFIRMED, null));
    }
}
