package com.arte.app.ainew;

import com.arte.ai.api.context.ResourceContextService;
import com.arte.ai.context.ResourceContextValues;
import com.arte.ai.model.context.ContextFragment;
import com.arte.ai.model.context.ResourceContextSelection;
import com.arte.ai.model.context.ResourceContextSnapshot;
import com.arte.ai.model.definition.DefinitionRef;
import com.arte.ai.model.execution.ExecutionOptions;
import com.arte.ai.model.execution.InvocationRequest;
import com.arte.ai.model.execution.QueuedModelCall;
import com.arte.ai.model.generation.GenerationRequest;
import com.arte.ai.model.generation.ModelOptions;
import com.arte.ai.spi.business.ResourceContextAdapter;
import com.arte.app.testsupport.MySqlTestScripts;
import com.arte.base.exception.BaseException;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.identity.PrincipalRef;
import com.arte.base.model.identity.PrincipalType;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.resource.SourceRef;
import com.google.gson.JsonParser;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ResourceContextPersistenceTest {
    final Instant now = Instant.parse("2026-10-03T12:00:00.123456Z");
    final DefinitionRef binding = new DefinitionRef("ai-binding", "model", "v1");
    final ExecutionContext viewer = ExecutionContext.create(new ExecutionScope("tenant", "workspace", new PrincipalRef("user", PrincipalType.USER)), "trace", Set.of("AI_PROCESS"));

    ResourceContextSnapshot snapshot() {
        var adapter = new ResourceContextAdapter() {
            public String resourceType() { return "ARTICLE"; }
            public ContextFragment resolve(ExecutionContext context, ResourceContextSelection selection, String citation) {
                return new ContextFragment(citation, new SourceRef(selection.resource(), citation), "😀资料", true, "用户草稿选区；UTF-16 范围 [1,5)");
            }
            public void authorize(ExecutionContext context, SourceRef source, boolean external) { }
        };
        var resource = ResourceRef.draft("ARTICLE", "10", "3", "draft", ResourceContextValues.textDigest("甲😀资料乙")).withRange("utf16:1:5");
        return new ResourceContextService(List.of(adapter), Clock.fixed(now, ZoneOffset.UTC), Duration.ofMinutes(10), 8192, 8192, 256)
                .prepare(viewer, binding, List.of(), null, new ResourceContextSelection(resource, "甲😀资料乙"), List.of(), 512);
    }

    QueuedModelCall call(ResourceContextSnapshot snapshot) {
        var messages = snapshot == null ? List.of(new com.arte.ai.model.message.Message(com.arte.ai.model.message.MessageRole.USER,
                List.of(new com.arte.ai.model.message.TextPart("旧纯文本")))) : snapshot.messages();
        return new QueuedModelCall(new InvocationRequest<>(new DefinitionRef("ai-capability", "model", "v1"), binding,
                new GenerationRequest(messages, new ModelOptions(null, 512), List.of(), null, snapshot),
                new ExecutionOptions(Duration.ofSeconds(90), true), viewer), ResourceRef.current("egress-consent", "consent"),
                "sha256:" + "a".repeat(64), now.plusSeconds(90));
    }

    @Test void snapshotRoundTripPreservesExactSourceBudgetAndMicroseconds() {
        var snapshot = snapshot();
        assertEquals(snapshot, ResourceContextJson.decode(ResourceContextJson.encode(snapshot)));
        assertNull(ResourceContextJson.decode(null));
        assertNull(ResourceContextJson.encode(null));
        assertFalse(ResourceContextJson.encode(snapshot).contains("甲😀资料乙"));
    }

    @Test void durableWorkRoundTripKeepsV2ContextAndV1TextCompatibility() {
        var next = call(snapshot()); var old = call(null);
        assertTrue(ModelWorkJson.encode(next).contains("arte.model.work.v2"));
        assertTrue(ModelWorkJson.encode(old).contains("arte.model.work.v1"));
        assertEquals(next, ModelWorkJson.decode(ModelWorkJson.encode(next)));
        assertEquals(old, ModelWorkJson.decode(ModelWorkJson.encode(old)));
    }

    @Test void alteredSourcesOrContentCannotKeepTheOriginalDigest() {
        var json = JsonParser.parseString(ResourceContextJson.encode(snapshot())).getAsJsonObject();
        json.getAsJsonArray("fragments").get(0).getAsJsonObject().addProperty("content", "被替换的资料");
        assertThrows(BaseException.class, () -> ResourceContextJson.decode(json.toString()));
        var work = JsonParser.parseString(ModelWorkJson.encode(call(snapshot()))).getAsJsonObject();
        work.getAsJsonObject("resourceContext").addProperty("digest", "sha256:" + "b".repeat(64));
        assertThrows(BaseException.class, () -> ModelWorkJson.decode(work.toString()));
    }

    @Test void unknownFormatsAndMissingContextAreRejected() {
        var json = JsonParser.parseString(ResourceContextJson.encode(snapshot())).getAsJsonObject();
        json.addProperty("format", "arte.resource.context.v99");
        assertThrows(IllegalArgumentException.class, () -> ResourceContextJson.decode(json.toString()));
        var work = JsonParser.parseString(ModelWorkJson.encode(call(snapshot()))).getAsJsonObject();
        work.remove("resourceContext");
        assertThrows(RuntimeException.class, () -> ModelWorkJson.decode(work.toString()));
    }

    @Test void upgradePreservesOldActionPayloadAndAddsNullableExecutionMetadata() throws Exception {
        var datasource = new JdbcDataSource();
        datasource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var jdbc = new JdbcTemplate(datasource);
        try (var connection = datasource.getConnection()) {
            String model = Files.readString(Path.of("scripts/arte-ai-new-model-ddl-mysql.sql"));
            model = model.replaceAll("(?m)^.*resource_context_json.*\\R", "");
            ScriptUtils.executeSqlScript(connection, MySqlTestScripts.h2Resource(model));
            ScriptUtils.executeSqlScript(connection, MySqlTestScripts.h2Resource(Files.readString(Path.of("scripts/arte-ai-new-action-ddl-mysql.sql"))));
            String legacy = "{\"format\":\"arte.action.input.v1\",\"legacy\":true}";
            jdbc.update("INSERT INTO arte_ai_new_action(action_id,scope_key,tenant_id,workspace_id,principal_type,principal_id,submission_key,request_digest,payload_json) VALUES('old',?,'tenant','workspace','USER','user','key',?,?)",
                    "a".repeat(64), "sha256:" + "b".repeat(64), legacy);
            assertThrows(RuntimeException.class, () -> new JdbcModelExecutionStore(jdbc, new DataSourceTransactionManager(datasource), Clock.systemUTC()));
            ScriptUtils.executeSqlScript(connection, MySqlTestScripts.h2Resource(Files.readString(Path.of("scripts/arte-ai-new-resource-context-ddl-mysql.sql"))));
            assertEquals(legacy, jdbc.queryForObject("SELECT payload_json FROM arte_ai_new_action WHERE action_id='old'", String.class));
            assertDoesNotThrow(() -> new JdbcModelExecutionStore(jdbc, new DataSourceTransactionManager(datasource), Clock.systemUTC()));
            var column = jdbc.queryForMap("SELECT IS_NULLABLE,COLUMN_DEFAULT FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='ARTE_AI_NEW_EXECUTION' AND COLUMN_NAME='RESOURCE_CONTEXT_JSON'");
            assertEquals("YES", column.get("IS_NULLABLE")); assertNull(column.get("COLUMN_DEFAULT"));
        } finally { jdbc.execute("DROP ALL OBJECTS"); }
    }
}
