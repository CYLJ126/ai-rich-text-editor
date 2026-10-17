package com.arte.ainew.search;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.config.NewAiSearchProperties;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.persistence.mybatis.MybatisConversationSearchSource;
import com.arte.ainew.persistence.mybatis.MybatisConversationSearchSyncStore;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationOptions;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.generation.ModelResult;
import com.arte.ainew.serialization.CanonicalJson;
import org.h2.jdbcx.JdbcDataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import reactor.core.publisher.Mono;

import javax.sql.DataSource;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.arte.ainew.serialization.CanonicalJson.key;

final class SearchSyncFixture implements AutoCloseable {

    static final ExecutionOwner OWNER = new ExecutionOwner("search-tenant", "search-space", "search-subject");
    static final Instant AT = Instant.parse("2026-10-15T12:00:00.123456Z");
    final DataSource ds;
    final JdbcTemplate jdbc;
    final JacksonExecutionRecordCodec codec = new JacksonExecutionRecordCodec();
    final Set<String> archived = new java.util.concurrent.CopyOnWriteArraySet<>();
    final MybatisConversationSearchSyncStore store;
    final MybatisConversationSearchSource source;
    final boolean h2;

    SearchSyncFixture() {
        this(h2());
    }

    SearchSyncFixture(DataSource ds) {
        this(ds, true);
    }

    SearchSyncFixture(DataSource ds, boolean initializeSchema) {
        this.ds = ds;
        h2 = ds instanceof JdbcDataSource;
        jdbc = new JdbcTemplate(ds);
        if (initializeSchema)
            new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql"), new ClassPathResource("arte-ai-new-search-sync-migration.sql")).execute(ds);
        store = new MybatisConversationSearchSyncStore(ds, 2);
        source = new MybatisConversationSearchSource(ds, codec, (owner, ids) -> Mono.just(ids.stream().filter(archived::contains).collect(java.util.stream.Collectors.toSet())));
    }

    private static DataSource h2() {
        var ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:search-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        return ds;
    }

    static NewAiSearchProperties properties(String prefix, String generation) {
        return new NewAiSearchProperties(true, false, true, true, true,
                new NewAiSearchProperties.Index(prefix + "-read", prefix + "-write", prefix, null, null, null, null, null, 1, 0), null, null,
                new NewAiSearchProperties.Sync(generation, Duration.ofMillis(100), 2, 10, Duration.ofSeconds(60), Duration.ofSeconds(1), Duration.ofMillis(100), Duration.ofSeconds(1), 2));
    }

    Conversation conversation(String id, String title) {
        var c = new Conversation(id, OWNER, title, 0, null, List.of(), Conversation.State.ACTIVE, AT, AT);
        jdbc.update("INSERT INTO arte_ai_conversation_new(id_key,owner_key,version_no,workspace_snapshot) VALUES(?,?,0,?)", key(id), key(OWNER.tenantId(), OWNER.workspaceId(), OWNER.subjectId()), codec.encode(c));
        return c;
    }

    void rename(String id, String title) {
        var c = codec.decode(jdbc.queryForObject("SELECT workspace_snapshot FROM arte_ai_conversation_new WHERE id_key=?", String.class, key(id)), Conversation.class);
        jdbc.update("UPDATE arte_ai_conversation_new SET workspace_snapshot=? WHERE id_key=?", codec.encode(new Conversation(id, c.owner(), title, c.version(), null, List.of(), c.state(), c.createdAt(), c.updatedAt())), key(id));
    }

    Turn turn(String conv, String id, String user, List<String> invocationIds) {
        var t = new Turn(id, conv, 1, null, null, message("user-" + id, ChatMessage.Role.USER, user), invocationIds, null, 0, AT, AT);
        jdbc.update("INSERT INTO arte_ai_turn(id_key,conversation_key,sequence_no,workspace_snapshot) VALUES(?,?,?,?)", key(id), key(conv), 1, codec.encode(t));
        return t;
    }

    Invocation invocation(String conv, String turn, String id, String text, boolean partial, boolean persistResult) {
        var auth = new ExecutionAuthorization(new ExecutionPrincipal(OWNER.subjectId(), "test", ExecutionPrincipal.Kind.USER), OWNER.tenantId(), OWNER.workspaceId(), Set.of("ai:invoke"), "grant");
        var context = new ExecutionContext(id, "trace", auth, AT.plusSeconds(300), null, null, null, "key-" + id);
        var input = new GenerationRequest(List.of(message("input-" + id, ChatMessage.Role.SYSTEM, "internal must not index")), new GenerationOptions(100, null, null, List.of()), List.of(), new GenerationRequest.TextOutput());
        var request = new InvocationRequest<>(new DefinitionRef("capability", "text", "v1"), new DefinitionRef("binding", "text", "v1"), input.kind(), input, new ExecutionOptions(context.deadline(), 1, 100000, 0, 0), context);
        var result = new InvocationResult.Generation(new ModelResult("result-" + id, new ModelResult.ModelIdentity("test", "model", null), List.of(message("answer-" + id, ChatMessage.Role.ASSISTANT, text)), partial ? ModelResult.FinishReason.LENGTH : ModelResult.FinishReason.STOP, !partial, null, Usage.unknown(), List.of()));
        String bytes = CanonicalJson.normalize(codec.encode(result));
        var ref = new ResultRef(key("result", id), "model-result", 1, CanonicalJson.sha256(bytes), partial);
        var invocation = new Invocation(request, "a".repeat(64), new Invocation.ConversationLink(conv, 0, turn), "context-" + id, null,
                partial ? Invocation.State.CANCELLED : Invocation.State.SUCCEEDED, 1, "attempt-" + id, ref, null, AT, AT);
        jdbc.update("INSERT INTO arte_ai_invocation(id_key,owner_key,workspace_snapshot,next_attempt,next_fence,next_sequence,retained_after,written_bytes) VALUES(?,?,?,1,1,1,0,0)", key(id), key(OWNER.tenantId(), OWNER.workspaceId(), OWNER.subjectId()), codec.encode(invocation));
        if (persistResult)
            jdbc.update("INSERT INTO arte_ai_result(id_key,owner_key,invocation_key,attempt_key,result_key,result_type,schema_version,partial,payload_digest,workspace_snapshot) VALUES(?,?,?,?,?,'model-result',1,?,?,?)",
                    ref.resultId(), key(OWNER.tenantId(), OWNER.workspaceId(), OWNER.subjectId()), key(id), key("attempt-" + id), key("operation-" + id), partial ? 1 : 0, ref.contentDigest(), bytes);
        return invocation;
    }

    static ChatMessage message(String id, ChatMessage.Role role, String text) {
        return new ChatMessage(id, role, List.of(new ChatMessage.Text(text)), List.of(), null);
    }

    void due() {
        jdbc.update("UPDATE arte_ai_search_checkpoint SET next_at=0");
        jdbc.update("UPDATE arte_ai_search_work SET next_at=0");
    }

    @Override
    public void close() {
        if (h2) jdbc.execute("DROP ALL OBJECTS");
    }
}
