package com.arte.app.ainew;

import com.arte.ai.conversation.ChatValues;
import com.arte.ai.model.context.ContextBudget;
import com.arte.ai.model.context.ContextSnapshot;
import com.arte.ai.model.context.ResourceContextSnapshot;
import com.arte.ai.model.conversation.*;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.spi.store.ChatStore;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionError;
import com.arte.base.model.execution.IdempotencyKey;
import com.arte.base.model.execution.ResultCertainty;
import com.arte.base.model.execution.SideEffectStatus;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import com.arte.base.spi.observability.Telemetry;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * 只操作新聊天表；会话锁分配顺序和占位，提交锁串行驱动，模型账本独立受理。
 */
public final class JdbcChatStore implements ChatStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writes;
    private final String jsonParameter;
    private final Telemetry telemetry;

    public JdbcChatStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this(jdbc, manager, Telemetry.disabled());
    }

    public JdbcChatStore(JdbcTemplate jdbc, PlatformTransactionManager manager, Telemetry telemetry) {
        this.telemetry = Objects.requireNonNull(telemetry);
        this.jdbc = Objects.requireNonNull(jdbc);
        jdbc.queryForList("SELECT estimator_version FROM arte_ai_new_context_token_budget WHERE 1=0");
        jdbc.queryForList("SELECT resource_context_json FROM arte_ai_new_turn WHERE 1=0");
        jdbc.queryForList("SELECT resource_context_json FROM arte_ai_new_context_snapshot WHERE 1=0");
        writes = new TransactionTemplate(Objects.requireNonNull(manager));
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        // H2 binds VARCHAR to JSON as a JSON string; FORMAT JSON requests native parsing.
        // MySQL validates raw bound strings on assignment to its JSON columns.
        try (var connection = Objects.requireNonNull(jdbc.getDataSource()).getConnection()) {
            jsonParameter = "H2".equals(connection.getMetaData().getDatabaseProductName()) ? "? FORMAT JSON" : "?";
        } catch (SQLException unavailable) {
            throw new IllegalStateException("chat datasource unavailable", unavailable);
        }
    }

    @Override
    public Conversation create(Conversation conversation) {
        return writes.execute(tx -> {
            var scope = conversation.scope();
            var binding = conversation.modelBindingRef();
            jdbc.update("INSERT INTO arte_ai_new_conversation(conversation_id,scope_key,tenant_id,workspace_id,principal_type,principal_id,title,model_binding_type,model_binding_id,model_binding_version,status,row_version,resource_refs_json,created_at,updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?," + jsonParameter + ",?,?)",
                    conversation.conversationId(), ModelKeys.scope(scope), scope.tenantId(), scope.workspaceId(), scope.principal().type().name(), scope.principal().principalId(),
                    conversation.title(), binding.definitionType(), binding.definitionId(), binding.version(), conversation.status().name(), conversation.version(),
                    ChatJson.resources(conversation.resources()), timestamp(conversation.createdAt()), timestamp(conversation.updatedAt()));
            return conversation(scope, conversation.conversationId()).orElseThrow();
        });
    }

    @Override
    public Optional<Conversation> conversation(ExecutionScope scope, String id) {
        return one("SELECT * FROM arte_ai_new_conversation WHERE conversation_id=? AND scope_key=?", conversationMapper(scope), id, ModelKeys.scope(scope));
    }

    @Override
    public List<Conversation> conversations(ExecutionScope scope, String title, int offset, int limit) {
        String match = title == null ? "" : title.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return jdbc.query("SELECT * FROM arte_ai_new_conversation WHERE scope_key=? AND status='ACTIVE' AND title LIKE ? ESCAPE '!' ORDER BY updated_at DESC,conversation_id DESC LIMIT ? OFFSET ?",
                conversationMapper(scope), ModelKeys.scope(scope), "%" + match + "%", limit, offset);
    }

    @Override
    public Conversation rename(ExecutionScope scope, String id, long version, String title, Instant now) {
        return writes.execute(tx -> {
            var previous = mutableConversation(scope, id, version);
            jdbc.update("UPDATE arte_ai_new_conversation SET title=?,row_version=row_version+1,updated_at=? WHERE conversation_id=? AND scope_key=?",
                    title, timestamp(later(now, previous.updatedAt())), id, ModelKeys.scope(scope));
            return conversation(scope, id).orElseThrow();
        });
    }

    @Override
    public Conversation delete(ExecutionScope scope, String id, long version, Instant now) {
        return writes.execute(tx -> {
            var previous = mutableConversation(scope, id, version);
            var time = timestamp(later(now, previous.updatedAt()));
            jdbc.update("UPDATE arte_ai_new_conversation SET status='DELETED',deleted_at=?,updated_at=?,row_version=row_version+1 WHERE conversation_id=? AND scope_key=?",
                    time, time, id, ModelKeys.scope(scope));
            return conversation(scope, id).orElseThrow();
        });
    }

    private Conversation mutableConversation(ExecutionScope scope, String id, long version) {
        var conversation = lockedConversation(scope, id);
        if (conversation.version() != version) throw failure(CommonErrorCode.VERSION_CONFLICT);
        if (activeTurn(scope, id).isPresent()) throw failure(CommonErrorCode.BUSY);
        return conversation;
    }

    private Conversation lockedConversation(ExecutionScope scope, String id) {
        var value = one("SELECT * FROM arte_ai_new_conversation WHERE conversation_id=? AND scope_key=? FOR UPDATE", conversationMapper(scope), id, ModelKeys.scope(scope))
                .orElseThrow(() -> failure(CommonErrorCode.NOT_FOUND));
        if (value.status() != ConversationStatus.ACTIVE) throw failure(CommonErrorCode.NOT_FOUND);
        return value;
    }

    @Override
    public Turn claim(Turn draft) {
        try {
            return writes.execute(tx -> {
                var scope = draft.scope();
                String key = ModelKeys.scope(scope);
                var conversation = lockedConversation(scope, draft.conversationId());
                var duplicate = one("SELECT * FROM arte_ai_new_turn WHERE scope_key=? AND idempotency_operation=? AND idempotency_key=?", turnMapper(scope), key, draft.idempotencyKey().operation(), draft.idempotencyKey().key());
                if (duplicate.isPresent()) {
                    if (!duplicate.get().idempotencyKey().requestDigest().equals(draft.idempotencyKey().requestDigest())
                            || !duplicate.get().conversationId().equals(draft.conversationId()))
                        throw failure(CommonErrorCode.IDEMPOTENCY_CONFLICT);
                    return duplicate.get();
                }
                if (conversation.version() != draft.conversationVersion())
                    throw failure(CommonErrorCode.VERSION_CONFLICT);
                if (activeTurn(scope, draft.conversationId()).isPresent()) throw failure(CommonErrorCode.BUSY);
                if (draft.kind() == TurnKind.REGENERATION) validateRegeneration(draft);
                long sequence = Math.addExact(jdbc.queryForObject("SELECT COALESCE(MAX(sequence_no),0) FROM arte_ai_new_turn WHERE conversation_id=? AND scope_key=?", Long.class, draft.conversationId(), key), 1);
                var time = timestamp(later(draft.createdAt(), conversation.updatedAt()));
                jdbc.update("INSERT INTO arte_ai_new_turn(turn_id,conversation_id,scope_key,sequence_no,conversation_version,row_version,kind,status,payload_format,input_json,model_options_json,resource_context_json,regenerates_turn_id,idempotency_operation,idempotency_key,request_digest,created_at,updated_at) VALUES (?,?,?,?,?,1,?,'PREPARING','arte.chat.turn.v1'," + jsonParameter + "," + jsonParameter + "," + jsonParameter + ",?,?,?,?,?,?)",
                        draft.turnId(), draft.conversationId(), key, sequence, draft.conversationVersion(), draft.kind().name(), ChatJson.messages(draft.input()), ChatJson.options(draft.modelOptions()), ResourceContextJson.encode(draft.resourceContext()),
                        draft.regeneratesTurnId(), draft.idempotencyKey().operation(), draft.idempotencyKey().key(), draft.idempotencyKey().requestDigest(), time, time);
                jdbc.update("UPDATE arte_ai_new_conversation SET row_version=row_version+1,updated_at=? WHERE conversation_id=? AND scope_key=?", time, draft.conversationId(), key);
                return turn(scope, draft.turnId()).orElseThrow();
            });
        } catch (DuplicateKeyException conflict) {
            throw failure(CommonErrorCode.IDEMPOTENCY_CONFLICT);
        }
    }

    private void validateRegeneration(Turn draft) {
        var previous = turn(draft.scope(), draft.regeneratesTurnId()).orElseThrow(() -> failure(CommonErrorCode.NOT_FOUND));
        if (!previous.conversationId().equals(draft.conversationId()) || previous.status() != TurnStatus.ACCEPTED || previous.occupiesConversationSlot())
            throw failure(CommonErrorCode.INVALID_ARGUMENT);
        var root = previous;
        for (int depth = 0; root.kind() == TurnKind.REGENERATION; depth++) {
            if (depth >= 128) throw failure(CommonErrorCode.UNSUPPORTED);
            var parent = turn(draft.scope(), root.regeneratesTurnId()).orElseThrow(() -> failure(CommonErrorCode.NOT_FOUND));
            if (!parent.conversationId().equals(draft.conversationId()) || parent.sequence() >= root.sequence())
                throw failure(CommonErrorCode.VERSION_CONFLICT);
            root = parent;
        }
        if (jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_turn WHERE conversation_id=? AND scope_key=? AND kind='MESSAGE' AND sequence_no>?", Integer.class,
                draft.conversationId(), ModelKeys.scope(draft.scope()), root.sequence()) > 0)
            throw failure(CommonErrorCode.VERSION_CONFLICT);
    }

    @Override
    public Optional<Turn> turn(ExecutionScope scope, String id) {
        return one("SELECT * FROM arte_ai_new_turn WHERE turn_id=? AND scope_key=?", turnMapper(scope), id, ModelKeys.scope(scope));
    }

    @Override
    public Optional<Turn> activeTurn(ExecutionScope scope, String conversationId) {
        return one("SELECT * FROM arte_ai_new_turn WHERE conversation_id=? AND scope_key=? AND slot_released_at IS NULL", turnMapper(scope), conversationId, ModelKeys.scope(scope));
    }

    @Override
    public List<Turn> turns(ExecutionScope scope, String conversationId, long beforeSequence, int limit) {
        return jdbc.query("SELECT * FROM arte_ai_new_turn WHERE conversation_id=? AND scope_key=? AND sequence_no<? ORDER BY sequence_no DESC LIMIT ?",
                turnMapper(scope), conversationId, ModelKeys.scope(scope), beforeSequence, limit);
    }

    @Override
    public <T> T withTurn(ExecutionScope scope, String id, Function<Turn, T> work) {
        long started = System.nanoTime();
        try {
            return writes.execute(tx -> {
                var turn = one("SELECT * FROM arte_ai_new_turn WHERE turn_id=? AND scope_key=? FOR UPDATE", turnMapper(scope), id, ModelKeys.scope(scope))
                        .orElseThrow(() -> failure(CommonErrorCode.NOT_FOUND));
                // Includes connection acquisition and the locking query; not a pure DB lock-wait measurement.
                telemetry.duration("arte.chat.turn.lock.acquire", Duration.ofNanos(System.nanoTime() - started),
                        Map.of(Telemetry.Label.COMPONENT, "ai-new", Telemetry.Label.OPERATION, "turn.lock"));
                return work.apply(turn);
            });
        } finally {
            telemetry.duration("arte.chat.turn.transaction", Duration.ofNanos(System.nanoTime() - started),
                    Map.of(Telemetry.Label.COMPONENT, "ai-new", Telemetry.Label.OPERATION, "turn.write"));
        }
    }

    @Override
    public Turn ready(Turn expected, ContextSnapshot snapshot, Instant now) {
        if (expected.status() != TurnStatus.PREPARING || !snapshot.scope().equals(expected.scope()) || !snapshot.conversationId().equals(expected.conversationId())
                || snapshot.conversationVersion() != expected.conversationVersion())
            throw failure(CommonErrorCode.VERSION_CONFLICT);
        ChatValues.verify(snapshot);
        var binding = snapshot.modelBindingRef();
        var budget = snapshot.budget();
        jdbc.update("INSERT INTO arte_ai_new_context_snapshot(snapshot_id,conversation_id,scope_key,conversation_version,model_binding_type,model_binding_id,model_binding_version,payload_format,messages_json,fragments_json,history_refs_json,resource_context_json,input_byte_limit,used_input_bytes,output_token_reserve,content_digest,created_at,expires_at) VALUES (?,?,?,?,?,?,?,?," + jsonParameter + "," + jsonParameter + "," + jsonParameter + "," + jsonParameter + ",?,?,?,?,?,?)",
                snapshot.snapshotId(), snapshot.conversationId(), ModelKeys.scope(snapshot.scope()), snapshot.conversationVersion(), binding.definitionType(), binding.definitionId(), binding.version(), budget.contextWindowTokens() == null ? "arte.chat.context.v1" : "arte.chat.context.v2",
                ChatJson.messages(snapshot.messages()), fragmentsJson(snapshot.resourceContext()), ChatJson.history(snapshot.history()), ResourceContextJson.encode(snapshot.resourceContext()), budget.inputByteLimit(), budget.usedInputBytes(), budget.outputTokenReserve(), snapshot.contentDigest(), timestamp(snapshot.createdAt()), timestamp(snapshot.expiresAt()));
        if (budget.contextWindowTokens() != null)
            jdbc.update("INSERT INTO arte_ai_new_context_token_budget(snapshot_id,context_window_tokens,input_token_limit,estimated_input_tokens,safety_token_reserve,estimator_version) VALUES (?,?,?,?,?,?)",
                    snapshot.snapshotId(), budget.contextWindowTokens(), budget.inputTokenLimit(), budget.estimatedInputTokens(), budget.safetyTokenReserve(), budget.estimatorVersion());
        changed(jdbc.update("UPDATE arte_ai_new_turn SET status='READY',context_snapshot_id=?,row_version=row_version+1,updated_at=? WHERE turn_id=? AND scope_key=? AND row_version=? AND status='PREPARING'",
                snapshot.snapshotId(), timestamp(later(now, expected.updatedAt())), expected.turnId(), ModelKeys.scope(expected.scope()), expected.version()));
        return turn(expected.scope(), expected.turnId()).orElseThrow();
    }

    @Override
    public Turn accept(Turn expected, String executionId, Instant now) {
        changed(jdbc.update("UPDATE arte_ai_new_turn SET status='ACCEPTED',execution_id=?,row_version=row_version+1,updated_at=? WHERE turn_id=? AND scope_key=? AND row_version=? AND status='READY'",
                executionId, timestamp(later(now, expected.updatedAt())), expected.turnId(), ModelKeys.scope(expected.scope()), expected.version()));
        return turn(expected.scope(), expected.turnId()).orElseThrow();
    }

    @Override
    public Turn reject(Turn expected, ExecutionError error, Instant now) {
        if (error.sideEffectStatus() != SideEffectStatus.NONE || error.resultCertainty() != ResultCertainty.CONFIRMED)
            throw failure(CommonErrorCode.INVALID_ARGUMENT);
        var time = timestamp(later(now, expected.updatedAt()));
        changed(jdbc.update("UPDATE arte_ai_new_turn SET status='REJECTED',rejection_code=?,rejection_stage=?,rejection_retryable=?,rejection_side_effect_status=?,rejection_result_certainty=?,rejection_correlation_id=?,slot_released_at=?,updated_at=?,row_version=row_version+1 WHERE turn_id=? AND scope_key=? AND row_version=? AND status IN ('PREPARING','READY')",
                error.code(), error.failureStage(), error.retryable(), error.sideEffectStatus().name(), error.resultCertainty().name(), error.correlationId(), time, time,
                expected.turnId(), ModelKeys.scope(expected.scope()), expected.version()));
        return turn(expected.scope(), expected.turnId()).orElseThrow();
    }

    @Override
    public Turn release(Turn expected, Instant now) {
        var time = timestamp(later(now, expected.updatedAt()));
        changed(jdbc.update("UPDATE arte_ai_new_turn SET slot_released_at=?,updated_at=?,row_version=row_version+1 WHERE turn_id=? AND scope_key=? AND row_version=? AND status='ACCEPTED' AND slot_released_at IS NULL",
                time, time, expected.turnId(), ModelKeys.scope(expected.scope()), expected.version()));
        return turn(expected.scope(), expected.turnId()).orElseThrow();
    }

    @Override
    public Optional<ContextSnapshot> snapshot(ExecutionScope scope, String id) {
        return one("SELECT * FROM arte_ai_new_context_snapshot WHERE snapshot_id=? AND scope_key=?", (rs, row) -> {
            if (!java.util.Set.of("arte.chat.context.v1", "arte.chat.context.v2").contains(rs.getString("payload_format")))
                throw failure(CommonErrorCode.UNSUPPORTED);
            var resources = ResourceContextJson.decode(rs.getString("resource_context_json"));
            if (!com.google.gson.JsonParser.parseString(fragmentsJson(resources)).equals(com.google.gson.JsonParser.parseString(rs.getString("fragments_json"))))
                throw failure(CommonErrorCode.VERSION_CONFLICT);
            var value = new ContextSnapshot(rs.getString("snapshot_id"), rs.getString("conversation_id"), scope, rs.getLong("conversation_version"), binding(rs),
                    ChatJson.messages(rs.getString("messages_json")), resources == null ? List.of() : resources.fragments(), ChatJson.history(rs.getString("history_refs_json")),
                    contextBudget(rs), rs.getString("content_digest"), instant(rs, "created_at"), instant(rs, "expires_at"), resources);
            ChatValues.verify(value);
            return value;
        }, id, ModelKeys.scope(scope));
    }

    private static String fragmentsJson(ResourceContextSnapshot resources) {
        return resources == null ? "[]" : com.google.gson.JsonParser.parseString(ResourceContextJson.encode(resources)).getAsJsonObject().get("fragments").toString();
    }

    private ContextBudget contextBudget(java.sql.ResultSet rs) throws java.sql.SQLException {
        int bytes = rs.getInt("input_byte_limit"), used = rs.getInt("used_input_bytes"), output = rs.getInt("output_token_reserve");
        if ("arte.chat.context.v1".equals(rs.getString("payload_format")))
            return new ContextBudget(bytes, used, output);
        return jdbc.queryForObject("SELECT * FROM arte_ai_new_context_token_budget WHERE snapshot_id=?", (row, index) ->
                new ContextBudget(bytes, used, output, row.getInt("context_window_tokens"), row.getInt("input_token_limit"), row.getInt("estimated_input_tokens"), row.getInt("safety_token_reserve"), row.getString("estimator_version")), rs.getString("snapshot_id"));
    }

    private RowMapper<Conversation> conversationMapper(ExecutionScope expected) {
        return (rs, row) -> {
            var scope = new ExecutionScope(rs.getString("tenant_id"), rs.getString("workspace_id"), new PrincipalRef(rs.getString("principal_id"), PrincipalType.valueOf(rs.getString("principal_type"))));
            if (!scope.equals(expected) || !ModelKeys.scope(scope).equals(rs.getString("scope_key")))
                throw failure(CommonErrorCode.NOT_FOUND);
            return new Conversation(rs.getString("conversation_id"), scope, rs.getString("title"), binding(rs), ConversationStatus.valueOf(rs.getString("status")), rs.getLong("row_version"),
                    ChatJson.resources(rs.getString("resource_refs_json")), instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "deleted_at"));
        };
    }

    private RowMapper<Turn> turnMapper(ExecutionScope scope) {
        return (rs, row) -> {
            if (!"arte.chat.turn.v1".equals(rs.getString("payload_format"))) throw failure(CommonErrorCode.UNSUPPORTED);
            ExecutionError error = rs.getString("rejection_code") == null ? null : new ExecutionError(rs.getString("rejection_code"), rs.getString("rejection_stage"), rs.getBoolean("rejection_retryable"),
                    SideEffectStatus.valueOf(rs.getString("rejection_side_effect_status")), ResultCertainty.valueOf(rs.getString("rejection_result_certainty")), rs.getString("rejection_correlation_id"));
            return new Turn(rs.getString("turn_id"), rs.getString("conversation_id"), scope, rs.getLong("sequence_no"), rs.getLong("conversation_version"), rs.getLong("row_version"),
                    TurnKind.valueOf(rs.getString("kind")), TurnStatus.valueOf(rs.getString("status")), ChatJson.messages(rs.getString("input_json")), ChatJson.options(rs.getString("model_options_json")),
                    rs.getString("regenerates_turn_id"), rs.getString("context_snapshot_id"), rs.getString("execution_id"),
                    new IdempotencyKey(rs.getString("idempotency_key"), rs.getString("idempotency_operation"), rs.getString("request_digest")), error, instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "slot_released_at"), ResourceContextJson.decode(rs.getString("resource_context_json")));
        };
    }

    private static DefinitionRef binding(ResultSet rs) throws SQLException {
        return new DefinitionRef(rs.getString("model_binding_type"), rs.getString("model_binding_id"), rs.getString("model_binding_version"));
    }

    private static Instant instant(ResultSet rs, String field) throws SQLException {
        var value = rs.getTimestamp(field);
        return value == null ? null : value.toInstant();
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant later(Instant now, Instant previous) {
        return now.isBefore(previous) ? previous : now;
    }

    private <T> Optional<T> one(String sql, RowMapper<T> mapper, Object... parameters) {
        var rows = jdbc.query(sql, mapper, parameters);
        if (rows.size() > 1) throw new IllegalStateException("ambiguous chat identity");
        return rows.stream().findFirst();
    }

    private static void changed(int rows) {
        if (rows != 1) throw failure(CommonErrorCode.VERSION_CONFLICT);
    }

    private static com.arte.base.exception.BaseException failure(CommonErrorCode code) {
        return ChatValues.failure(code, "chat-store");
    }
}
