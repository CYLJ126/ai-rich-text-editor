package com.arte.app.ainew;

import com.arte.ai.conversation.ChatValues;
import com.arte.ai.model.context.ResourceContextSnapshot;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.identity.ExecutionScope;
import org.springframework.jdbc.core.JdbcTemplate;

/** 预览保存实际输入，提交不再次检索；过期记录保留以恢复已经受理的幂等请求。 */
public final class JdbcRetrievalPreviewStore {
    public record Preview(String previewId, String conversationId, long conversationVersion, String requestDigest,
                          ResourceContextSnapshot context) { }
    private final JdbcTemplate jdbc;
    private final String jsonParameter;
    public JdbcRetrievalPreviewStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        jdbc.queryForList("SELECT preview_id FROM arte_ai_new_retrieval_preview WHERE 1=0");
        try (var connection = jdbc.getDataSource().getConnection()) {
            jsonParameter = "H2".equals(connection.getMetaData().getDatabaseProductName()) ? "? FORMAT JSON" : "?";
        } catch (java.sql.SQLException error) { throw new IllegalStateException("preview datasource unavailable", error); }
    }
    public Preview save(String conversation, long version, String digest, ResourceContextSnapshot snapshot) {
        jdbc.update("INSERT INTO arte_ai_new_retrieval_preview(preview_id,scope_key,conversation_id,conversation_version,request_digest,context_json,created_at,expires_at) VALUES (?,?,?,?,?," + jsonParameter + ",?,?)",
                snapshot.snapshotId(), ModelKeys.scope(snapshot.scope()), conversation, version, digest, ResourceContextJson.encode(snapshot),
                java.sql.Timestamp.from(snapshot.createdAt()), java.sql.Timestamp.from(snapshot.expiresAt()));
        return new Preview(snapshot.snapshotId(), conversation, version, digest, snapshot);
    }
    public Preview find(ExecutionScope scope, String id) {
        if (id == null || !id.matches("[a-zA-Z0-9-]{1,64}")) throw new IllegalArgumentException("invalid preview id");
        return jdbc.query("SELECT * FROM arte_ai_new_retrieval_preview WHERE scope_key=? AND preview_id=?", (row, index) -> {
            var context = ResourceContextJson.decode(row.getString("context_json"));
            if (!context.scope().equals(scope) || !context.snapshotId().equals(row.getString("preview_id"))
                    || !context.createdAt().equals(row.getTimestamp("created_at").toInstant())
                    || !context.expiresAt().equals(row.getTimestamp("expires_at").toInstant()))
                throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "rag-preview");
            return new Preview(context.snapshotId(), row.getString("conversation_id"), row.getLong("conversation_version"), row.getString("request_digest"), context);
        }, ModelKeys.scope(scope), id).stream().findFirst().orElseThrow(() -> ChatValues.failure(CommonErrorCode.NOT_FOUND, "rag-preview"));
    }
}
