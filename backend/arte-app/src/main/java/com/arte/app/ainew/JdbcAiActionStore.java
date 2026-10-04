package com.arte.app.ainew;

import com.arte.ai.conversation.ChatValues;
import com.arte.ai.model.action.AiActionExecution;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.model.execution.ExecutionOptions;
import com.arte.ai.spi.store.AiActionStore;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.IdempotencyKey;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Optional;

/**
 * 动作输入独立提交；模型以 action:UUID 幂等受理，关联可查询恢复，不持有事务等待模型。
 */
public final class JdbcAiActionStore implements AiActionStore {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate writes;

    public JdbcAiActionStore(JdbcTemplate jdbc, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        jdbc.queryForList("SELECT request_digest,payload_json FROM arte_ai_new_action WHERE 1=0");
        writes = new TransactionTemplate(manager);
        writes.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        writes.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public AiActionExecution claim(AiActionExecution draft) {
        try {
            return writes.execute(tx -> {
                var existing = findIdempotent(draft.scope(), draft.submissionKey().key());
                if (existing.isPresent()) return same(existing.get(), draft);
                var scope = draft.scope();
                jdbc.update("INSERT INTO arte_ai_new_action(action_id,scope_key,tenant_id,workspace_id,principal_type,principal_id,submission_key,request_digest,payload_json,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                        draft.actionExecutionId(), ModelKeys.scope(scope), scope.tenantId(), scope.workspaceId(), scope.principal().type().name(),
                        scope.principal().principalId(), draft.submissionKey().key(), draft.submissionKey().requestDigest(), encode(draft), Timestamp.from(draft.createdAt()));
                return draft;
            });
        } catch (DuplicateKeyException raced) {
            return same(findIdempotent(draft.scope(), draft.submissionKey().key()).orElseThrow(() -> raced), draft);
        }
    }

    private static AiActionExecution same(AiActionExecution existing, AiActionExecution draft) {
        if (!existing.submissionKey().requestDigest().equals(draft.submissionKey().requestDigest()))
            throw ChatValues.failure(CommonErrorCode.IDEMPOTENCY_CONFLICT, "action-idempotency");
        return existing;
    }

    @Override
    public Optional<AiActionExecution> find(ExecutionScope scope, String id) {
        return jdbc.query("SELECT * FROM arte_ai_new_action WHERE scope_key=? AND action_id=?", this::decode,
                ModelKeys.scope(scope), id).stream().findFirst();
    }

    @Override
    public Optional<AiActionExecution> findIdempotent(ExecutionScope scope, String key) {
        return jdbc.query("SELECT * FROM arte_ai_new_action WHERE scope_key=? AND submission_key=?", this::decode,
                ModelKeys.scope(scope), key).stream().findFirst();
    }

    private static String encode(AiActionExecution action) {
        var o = new JsonObject();
        o.addProperty("format", action.resourceContext() == null ? "arte.action.input.v1" : "arte.action.input.v2");
        if (action.resourceContext() != null) o.add("resourceContext", JsonParser.parseString(ResourceContextJson.encode(action.resourceContext())));
        o.add("action", ref(action.actionRef()));
        o.add("capability", ref(action.capabilityRef()));
        o.add("binding", ref(action.bindingRef()));
        o.add("messages", JsonParser.parseString(ChatJson.messages(action.input())));
        o.add("options", JsonParser.parseString(ChatJson.options(action.modelOptions())));
        o.addProperty("timeout", action.executionOptions().timeout().toString());
        o.addProperty("streaming", action.executionOptions().streaming());
        o.addProperty("original", action.regeneratesActionId());
        return o.toString();
    }

    private AiActionExecution decode(ResultSet row, int index) throws SQLException {
        var o = JsonParser.parseString(row.getString("payload_json")).getAsJsonObject();
        if (!java.util.Set.of("arte.action.input.v1", "arte.action.input.v2").contains(ModelJson.value(o, "format")))
            throw new IllegalArgumentException("unsupported action input format");
        var scope = new ExecutionScope(row.getString("tenant_id"), row.getString("workspace_id"),
                new PrincipalRef(row.getString("principal_id"), PrincipalType.valueOf(row.getString("principal_type"))));
        return new AiActionExecution(row.getString("action_id"), scope, ref(o.getAsJsonObject("action")),
                ref(o.getAsJsonObject("capability")), ref(o.getAsJsonObject("binding")), ChatJson.messages(o.get("messages").toString()),
                ChatJson.options(o.get("options").toString()), new ExecutionOptions(Duration.parse(ModelJson.value(o, "timeout")), o.get("streaming").getAsBoolean()),
                ModelJson.value(o, "original"), new IdempotencyKey(row.getString("submission_key"), "ai.action.submit", row.getString("request_digest")), row.getTimestamp("created_at").toInstant(), "arte.action.input.v2".equals(ModelJson.value(o, "format")) ? ResourceContextJson.decode(o.get("resourceContext").toString()) : null);
    }

    private static JsonObject ref(DefinitionRef ref) {
        var o = new JsonObject();
        o.addProperty("type", ref.definitionType());
        o.addProperty("id", ref.definitionId());
        o.addProperty("version", ref.version());
        return o;
    }

    private static DefinitionRef ref(JsonObject o) {
        return new DefinitionRef(ModelJson.value(o, "type"), ModelJson.value(o, "id"), ModelJson.value(o, "version"));
    }
}
