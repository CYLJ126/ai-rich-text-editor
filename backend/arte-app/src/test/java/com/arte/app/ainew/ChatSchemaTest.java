package com.arte.app.ainew;

import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests production table constraints in H2; MySQL deployment syntax still needs a MySQL check.
 */
class ChatSchemaTest {
    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final String SCOPE = scope("user");
    private static final String OTHER_SCOPE = scope("other");
    private JdbcTemplate jdbc;
    private Connection schemaConnection;

    @BeforeEach
    void setup() throws Exception {
        var datasource = new JdbcDataSource();
        datasource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(datasource);
        String modelSql = Files.readString(Path.of("scripts", "arte-ai-new-model-ddl-mysql.sql"));
        String chatSql = Files.readString(Path.of("scripts", "arte-ai-new-chat-ddl-mysql.sql"));
        // Replace only the MySQL-specific index migration and storage declarations.
        // The generated expression, keys, CHECK constraints and FKs are read from the actual script.
        chatSql = chatSql.substring(chatSql.indexOf("-- CHAT_TABLES_BEGIN"))
                .replace(") STORED,", "),");
        // H2 2.4 caches the defining session in constant IN expressions in CHECKs.
        // Keep that session open while exercising constraints through independent JDBC connections.
        schemaConnection = datasource.getConnection();
        ScriptUtils.executeSqlScript(schemaConnection, resource(modelSql));
        jdbc.execute("ALTER TABLE arte_ai_new_execution ADD CONSTRAINT uq_ai_new_execution_scope UNIQUE(execution_id, scope_key)");
        ScriptUtils.executeSqlScript(schemaConnection, resource(chatSql));
        conversation("conversation", SCOPE, "user");
        conversation("second", SCOPE, "user");
        conversation("other", OTHER_SCOPE, "other");
    }

    @AfterEach
    void cleanup() throws Exception {
        if (schemaConnection != null) {
            try {
                jdbc.execute("DROP ALL OBJECTS");
            } finally {
                schemaConnection.close();
            }
        }
    }

    @Test
    void oneActiveSubmissionPerConversationSurvivesBeyondAcceptance() {
        preparing("first", "conversation", SCOPE, 1, "key-1");
        denied(() -> preparing("blocked", "conversation", SCOPE, 2, "key-2"));
        snapshot("snapshot", "conversation", SCOPE, 1);
        String execution = execution(SCOPE);
        jdbc.update("UPDATE arte_ai_new_turn SET status='ACCEPTED',context_snapshot_id='snapshot',execution_id=? WHERE turn_id='first'", execution);
        denied(() -> preparing("still-blocked", "conversation", SCOPE, 2, "key-2"));
        jdbc.update("UPDATE arte_ai_new_execution SET status='SUCCEEDED' WHERE execution_id=?", execution);
        jdbc.update("UPDATE arte_ai_new_turn SET slot_released_at=updated_at WHERE turn_id='first'");
        preparing("next", "conversation", SCOPE, 2, "key-2");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_turn WHERE conversation_id='conversation'", Integer.class));
        assertEquals("ACCEPTED", jdbc.queryForObject("SELECT status FROM arte_ai_new_turn WHERE turn_id='first'", String.class));
    }

    @Test
    void pendingSubmissionCannotReleaseSlotWithoutAConfirmedOutcome() {
        preparing("first", "conversation", SCOPE, 1, "key");
        denied(() -> jdbc.update("UPDATE arte_ai_new_turn SET slot_released_at=updated_at WHERE turn_id='first'"));
        rejected("first");
        preparing("next", "conversation", SCOPE, 2, "next-key");
    }

    @Test
    void allReferencesRemainInsideTheirConversationAndOwnerScope() {
        denied(() -> preparing("wrong-owner", "conversation", OTHER_SCOPE, 1, "key"));
        snapshot("other-snapshot", "other", OTHER_SCOPE, 1);
        snapshot("second-snapshot", "second", SCOPE, 1);
        preparing("first", "conversation", SCOPE, 1, "key");
        denied(() -> ready("first", "other-snapshot"));
        denied(() -> ready("first", "second-snapshot"));
        snapshot("own-snapshot", "conversation", SCOPE, 1);
        ready("first", "own-snapshot");
        String otherExecution = execution(OTHER_SCOPE);
        denied(() -> jdbc.update("UPDATE arte_ai_new_turn SET status='ACCEPTED',execution_id=? WHERE turn_id='first'", otherExecution));
    }

    @Test
    void contextMustMatchTheVersionActuallySelectedForSubmission() {
        snapshot("snapshot-v2", "conversation", SCOPE, 2);
        preparing("first", "conversation", SCOPE, 1, "key");
        denied(() -> ready("first", "snapshot-v2"));
        snapshot("snapshot-v1", "conversation", SCOPE, 1);
        ready("first", "snapshot-v1");
        assertEquals("READY", jdbc.queryForObject("SELECT status FROM arte_ai_new_turn WHERE turn_id='first'", String.class));
    }

    @Test
    void idempotencyAndSubmissionOrderCannotBeReusedAfterSlotRelease() {
        preparing("first", "conversation", SCOPE, 1, "same-key");
        rejected("first");
        denied(() -> preparing("duplicate", "second", SCOPE, 1, "same-key"));
        denied(() -> preparing("same-sequence", "conversation", SCOPE, 1, "new-key"));
        preparing("other-owner", "other", OTHER_SCOPE, 1, "same-key");
        preparing("next", "conversation", SCOPE, 2, "new-key");
    }

    @Test
    void regenerationPreservesOriginalAndCannotPointAcrossConversations() {
        preparing("original", "conversation", SCOPE, 1, "key");
        rejected("original");
        preparing("regenerated", "conversation", SCOPE, 2, "regeneration-key");
        denied(() -> jdbc.update("UPDATE arte_ai_new_turn SET kind='REGENERATION',idempotency_operation='chat.turn.regenerate',regenerates_turn_id='regenerated' WHERE turn_id='regenerated'"));
        preparing("foreign-original", "second", SCOPE, 1, "foreign-key");
        denied(() -> jdbc.update("UPDATE arte_ai_new_turn SET kind='REGENERATION',idempotency_operation='chat.turn.regenerate',regenerates_turn_id='foreign-original' WHERE turn_id='regenerated'"));
        jdbc.update("UPDATE arte_ai_new_turn SET kind='REGENERATION',idempotency_operation='chat.turn.regenerate',regenerates_turn_id='original' WHERE turn_id='regenerated'");
        assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_turn WHERE conversation_id='conversation'", Integer.class));
    }

    @Test
    void uncertainAcceptanceIsNotARejectedSubmission() {
        preparing("first", "conversation", SCOPE, 1, "key");
        denied(() -> jdbc.update("UPDATE arte_ai_new_turn SET status='REJECTED',slot_released_at=updated_at,rejection_code='unknown',rejection_stage='accept',rejection_retryable=FALSE,rejection_side_effect_status='UNKNOWN',rejection_result_certainty='UNKNOWN' WHERE turn_id='first'"));
        denied(() -> jdbc.update("UPDATE arte_ai_new_turn SET status='REJECTED',slot_released_at=updated_at WHERE turn_id='first'"));
        rejected("first");
    }

    @Test
    void conversationVersionSupportsStaleCommandDetectionAndSoftDeletion() {
        assertEquals(1, jdbc.update("UPDATE arte_ai_new_conversation SET title='new title',row_version=row_version+1 WHERE conversation_id='conversation' AND scope_key=? AND row_version=1 AND status='ACTIVE'", SCOPE));
        assertEquals(0, jdbc.update("UPDATE arte_ai_new_conversation SET title='stale',row_version=row_version+1 WHERE conversation_id='conversation' AND scope_key=? AND row_version=1 AND status='ACTIVE'", SCOPE));
        denied(() -> jdbc.update("UPDATE arte_ai_new_conversation SET status='DELETED' WHERE conversation_id='conversation'"));
        jdbc.update("UPDATE arte_ai_new_conversation SET status='DELETED',deleted_at=updated_at,row_version=row_version+1 WHERE conversation_id='conversation'");
        assertEquals(0, jdbc.update("UPDATE arte_ai_new_conversation SET title='late' WHERE conversation_id='conversation' AND status='ACTIVE'"));
    }

    private static ByteArrayResource resource(String sql) {
        String h2 = sql.replaceAll("(?i)\\s+ENGINE\\s*=\\s*InnoDB\\s+DEFAULT\\s+CHARSET\\s*=\\s*utf8mb4\\s+COLLATE\\s*=\\s*utf8mb4_bin", "");
        return new ByteArrayResource(h2.getBytes(StandardCharsets.UTF_8));
    }

    private void conversation(String id, String scope, String principal) {
        jdbc.update("INSERT INTO arte_ai_new_conversation(conversation_id,scope_key,tenant_id,workspace_id,principal_type,principal_id,title,model_binding_type,model_binding_id,model_binding_version,status,row_version,resource_refs_json,created_at,updated_at) VALUES (?,?,'tenant','workspace','USER',?,'chat','ai-binding','chat','1','ACTIVE',1,'[]',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, scope, principal);
    }

    private void snapshot(String id, String conversation, String scope, long version) {
        jdbc.update("INSERT INTO arte_ai_new_context_snapshot(snapshot_id,conversation_id,scope_key,conversation_version,model_binding_type,model_binding_id,model_binding_version,payload_format,messages_json,fragments_json,history_refs_json,input_byte_limit,used_input_bytes,output_token_reserve,content_digest,created_at,expires_at) VALUES (?,?,?,?,'ai-binding','chat','1','arte.chat.context.v1','[{\"role\":\"USER\",\"parts\":[{\"type\":\"text\",\"text\":\"question\"}]}]','[]','[]',1024,8,100,?,CURRENT_TIMESTAMP,DATEADD('MINUTE',10,CURRENT_TIMESTAMP))", id, conversation, scope, version, DIGEST);
    }

    private void preparing(String id, String conversation, String scope, long sequence, String key) {
        jdbc.update("INSERT INTO arte_ai_new_turn(turn_id,conversation_id,scope_key,sequence_no,conversation_version,row_version,kind,status,payload_format,input_json,model_options_json,idempotency_operation,idempotency_key,request_digest,created_at,updated_at) VALUES (?,?,?,?,1,1,'MESSAGE','PREPARING','arte.chat.turn.v1','[{\"role\":\"USER\",\"parts\":[{\"type\":\"text\",\"text\":\"question\"}]}]','{\"maxOutputTokens\":100}','chat.turn.submit',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)", id, conversation, scope, sequence, key, DIGEST);
    }

    private void ready(String turn, String snapshot) {
        jdbc.update("UPDATE arte_ai_new_turn SET status='READY',context_snapshot_id=? WHERE turn_id=?", snapshot, turn);
    }

    private void rejected(String turn) {
        jdbc.update("UPDATE arte_ai_new_turn SET status='REJECTED',slot_released_at=updated_at,rejection_code='denied',rejection_stage='admission',rejection_retryable=FALSE,rejection_side_effect_status='NONE',rejection_result_certainty='CONFIRMED' WHERE turn_id=?", turn);
    }

    private String execution(String scope) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO arte_ai_new_execution(execution_id,attempt_id,scope_key,identity_key,request_digest,capability_id,capability_version,binding_id,binding_version,connection_id,connection_version,status,revision,dispatched,accepted_at,reserved_amount,currency,budget_status) VALUES (?,?,?,?,?,'model','1','chat','1','connection','1','QUEUED',1,FALSE,CURRENT_TIMESTAMP,1,'USD','RESERVED')", id, UUID.randomUUID().toString(), scope, ModelKeys.hash(id), DIGEST);
        return id;
    }

    private static String scope(String user) {
        return ModelKeys.scope(new ExecutionScope("tenant", "workspace", new PrincipalRef(user, PrincipalType.USER)));
    }

    private void denied(Runnable operation) {
        assertThrows(DataIntegrityViolationException.class, operation::run);
    }
}
