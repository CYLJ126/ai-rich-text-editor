package com.arte.ainew.persistence;

import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.common.value.StructuredValue;
import com.arte.ainew.common.value.StructuredValue.ArrayValue;
import com.arte.ainew.common.value.StructuredValue.BooleanValue;
import com.arte.ainew.common.value.StructuredValue.ObjectValue;
import com.arte.ainew.common.value.StructuredValue.StringValue;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.persistence.mybatis.MybatisToolCatalogStore;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static com.arte.ainew.pojo.execution.StoreOutcome.Code.*;
import static org.junit.Assert.*;

/**
 * 两个独立 MyBatis 适配器共享 H2，验证真正的事务、防漂移、故障及版本读取。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/17 13:52 ✾
 */
public class ToolCatalogStoreTest {

    private static final Duration WAIT = Duration.ofSeconds(20);
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-17T01:02:03.123456789Z"), ZoneOffset.UTC);
    private final JacksonExecutionRecordCodec codec = new JacksonExecutionRecordCodec();
    private JdbcDataSource dataSource;
    private JdbcTemplate jdbc;
    private Scheduler scheduler;
    private MybatisToolCatalogStore first, second;

    @Before
    public void setup() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        // 在既有基线之上执行增量，证明脚本不依赖从空库重新开始。
        var baseline = new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql"));
        baseline.execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("DROP TABLE arte_ai_tool_definition");
        jdbc.execute("DROP TABLE arte_ai_tool_schema");
        jdbc.execute("DROP TABLE arte_ai_tool_provider");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-tool-catalog-migration.sql")).execute(dataSource);
        scheduler = Schedulers.newBoundedElastic(8, 128, "tool-catalog-test");
        first = new MybatisToolCatalogStore(dataSource, codec, scheduler, CLOCK);
        second = new MybatisToolCatalogStore(dataSource, codec, scheduler, CLOCK);
    }

    @After
    public void cleanup() {
        if (scheduler != null) scheduler.dispose();
        if (jdbc != null) jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    public void constructingTheStoreDoesNotConnectOrExecuteDdl() {
        var forbidden = new DelegatingDataSource(dataSource) {
            @Override
            public Connection getConnection() {
                throw new AssertionError("Construction must not access a database");
            }

            @Override
            public Connection getConnection(String user, String password) {
                return getConnection();
            }
        };
        assertNotNull(new MybatisToolCatalogStore(forbidden, codec, scheduler, CLOCK));
        assertEquals(0, count("provider"));
    }

    @Test
    public void anAtomicBatchRoundTripsAllThreeArtifactsAcrossInstances() {
        var batch = batch("v1");
        var pending = first.put(batch);
        assertEquals(0, count("provider"));
        assertEquals(APPLIED, pending.block(WAIT).code());
        assertEquals(batch.providers().getFirst(), second.findProvider(provider("v1").definition()).block(WAIT));
        for (var schema : batch.schemas()) assertEquals(schema, second.findSchema(schema.definition()).block(WAIT));
        var tool = batch.tools().getFirst();
        assertEquals(tool, second.findTool(tool.tool().definition()).block(WAIT));
        assertEquals(CapabilityDescriptor.Availability.DISCOVERED, tool.tool().discoveredCapability().availability());
        assertEquals(1, count("provider"));
        assertEquals(2, count("schema"));
        assertEquals(1, count("definition"));
        assertEquals(Long.valueOf(1_792_198_923_123456L), jdbc.queryForObject("SELECT created_micros FROM arte_ai_tool_provider", Long.class));
    }

    @Test
    public void exactVersionReplaysDoNotReplaceFactsOrCreationTime() {
        var batch = batch("v1");
        first.put(batch).block(WAIT);
        var later = new MybatisToolCatalogStore(dataSource, codec, scheduler, Clock.offset(CLOCK, Duration.ofDays(1)));
        assertEquals(REPLAYED, later.put(batch).block(WAIT).code());
        assertEquals(1, count("definition"));
        assertEquals(Long.valueOf(1_792_198_923_123456L), jdbc.queryForObject("SELECT created_micros FROM arte_ai_tool_definition", Long.class));
    }

    @Test
    public void versionLabelsAreExactAndNeverResolvedAsLatest() {
        for (String version : List.of("v1", "V1", "v10", "v2"))
            assertEquals(APPLIED, first.put(batch(version)).block(WAIT).code());
        assertEquals(4, count("provider"));
        assertEquals("v10", first.findTool(ref("tool", "text.count", "v10")).block(WAIT).tool().definition().version());
        assertNull(first.findTool(ref("tool", "text.count", "missing")).block(WAIT));
        assertThrows(IllegalArgumentException.class, () -> ref("schema", "input", "latest"));
    }

    @Test
    public void changedToolContentCannotReuseItsImmutableVersion() {
        var original = batch("v1");
        first.put(original).block(WAIT);
        var changed = revise(original.tools().getFirst(), "changed", object());
        assertEquals(IDEMPOTENCY_CONFLICT, second.put(new ToolCatalogStore.Batch(List.of(), List.of(), List.of(changed))).block(WAIT).code());
        assertEquals(original.tools().getFirst(), first.findTool(changed.tool().definition()).block(WAIT));
    }

    @Test
    public void changedTargetOrProviderRevisionCannotReuseAToolVersion() {
        var batch = batch("v1");
        first.put(batch).block(WAIT);
        var old = batch.tools().getFirst();
        var target = new ToolDefinitionRevision(old.tool(), old.provider(), new ExecutionTarget.Local(ref("tool-adapter", "other", "v1")));
        assertEquals(IDEMPOTENCY_CONFLICT, first.put(new ToolCatalogStore.Batch(List.of(), List.of(), List.of(target))).block(WAIT).code());
        var differentProvider = new ToolDefinitionRevision(old.tool(), provider("v2").definition(), old.target());
        assertEquals(IDEMPOTENCY_CONFLICT, first.put(new ToolCatalogStore.Batch(List.of(provider("v2")), List.of(), List.of(differentProvider))).block(WAIT).code());
        assertNull(first.findProvider(provider("v2").definition()).block(WAIT));
    }

    @Test
    public void changedSchemaOrProviderCannotReuseAnExistingVersion() {
        var batch = batch("v1");
        first.put(batch).block(WAIT);
        var schema = schema(ref("schema", "object", "v1"), object("type", text("object"), "additionalProperties", new BooleanValue(true)));
        assertEquals(IDEMPOTENCY_CONFLICT, second.put(new ToolCatalogStore.Batch(List.of(), List.of(schema), List.of())).block(WAIT).code());
        var old = provider("v1");
        var provider = new ToolProviderDefinition(old.definition(), old.kind(), ref("tool-provider-adapter", "changed", "v1"), null);
        assertEquals(IDEMPOTENCY_CONFLICT, second.put(new ToolCatalogStore.Batch(List.of(provider), List.of(), List.of())).block(WAIT).code());
    }

    @Test
    public void conflictOrMissingDependenciesLeavesNoPartialNewArtifacts() {
        var original = batch("v1");
        first.put(original).block(WAIT);
        var conflicting = revise(original.tools().getFirst(), "changed", object());
        var newProvider = provider("v2");
        assertEquals(IDEMPOTENCY_CONFLICT, first.put(new ToolCatalogStore.Batch(List.of(newProvider), List.of(), List.of(conflicting))).block(WAIT).code());
        assertNull(first.findProvider(newProvider.definition()).block(WAIT));
        var absent = batch("v3");
        assertEquals(NOT_FOUND, first.put(new ToolCatalogStore.Batch(absent.providers(), List.of(), absent.tools())).block(WAIT).code());
        assertNull(first.findProvider(provider("v3").definition()).block(WAIT));
    }

    @Test
    public void sqlFailureAfterEarlierInsertsRollsBackTheWholeBatch() {
        jdbc.execute("ALTER TABLE arte_ai_tool_definition ADD CONSTRAINT injected_failure CHECK (created_micros < 0)");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> first.put(batch("v1")).block(WAIT));
        assertEquals(0, count("provider"));
        assertEquals(0, count("schema"));
        assertEquals(0, count("definition"));
        assertEquals(Integer.valueOf(0), jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_lock", Integer.class));
        jdbc.execute("ALTER TABLE arte_ai_tool_definition DROP CONSTRAINT injected_failure");
        assertEquals(APPLIED, second.put(batch("v1")).block(WAIT).code());
    }

    @Test
    public void twoInstancesRegisterTheSameVersionOnlyOnce() {
        var batch = batch("v1");
        var results = Mono.zip(first.put(batch), second.put(batch)).block(WAIT);
        assertEquals(Set.of(APPLIED, REPLAYED), Set.of(results.getT1().code(), results.getT2().code()));
        assertEquals(1, count("definition"));
    }

    @Test
    public void multipleConcurrentSubscribersShareOneImmutableRegistration() {
        var batch = batch("v1");
        var results = reactor.core.publisher.Flux.range(0, 12)
                .flatMap(i -> (i % 2 == 0 ? first : second).put(batch), 8).collectList().block(WAIT);
        assertEquals(1, results.stream().filter(r -> r.code() == APPLIED).count());
        assertEquals(11, results.stream().filter(r -> r.code() == REPLAYED).count());
        assertEquals(1, count("definition"));
    }

    @Test
    public void concurrentDriftHasOneWinnerAndOneExplicitConflict() {
        var original = batch("v1");
        var changed = new ToolCatalogStore.Batch(original.providers(), original.schemas(), List.of(revise(original.tools().getFirst(), "changed", object())));
        var results = Mono.zip(first.put(original), second.put(changed)).block(WAIT);
        assertEquals(Set.of(APPLIED, IDEMPOTENCY_CONFLICT), Set.of(results.getT1().code(), results.getT2().code()));
        assertEquals(1, count("definition"));
        var winner = results.getT1().code() == APPLIED ? original : changed;
        assertEquals(winner.tools().getFirst(), second.findTool(ref("tool", "text.count", "v1")).block(WAIT));
    }

    @Test
    public void concurrentBatchesWithReorderedSharedDependenciesDoNotDeadlock() {
        var batch = batch("v1");
        var reversed = new ArrayList<>(batch.schemas());
        Collections.reverse(reversed);
        var other = new ToolCatalogStore.Batch(batch.providers(), reversed, batch.tools());
        var results = Mono.zip(first.put(batch), second.put(other)).block(WAIT);
        assertTrue(results.getT1().successful());
        assertTrue(results.getT2().successful());
        assertEquals(2, count("schema"));
    }

    @Test
    public void databaseUniquenessAlsoRejectsDuplicateIdentityAndVersionProjections() {
        first.put(batch("v1")).block(WAIT);
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "INSERT INTO arte_ai_tool_provider SELECT ?,identity_key,version_key,payload_digest,workspace_snapshot,created_micros FROM arte_ai_tool_provider",
                "f".repeat(64)));
        assertEquals(1, count("provider"));
    }

    @Test
    public void databaseForeignKeysRejectOrphanedDefinitions() {
        first.put(batch("v1")).block(WAIT);
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.update(
                "UPDATE arte_ai_tool_definition SET provider_key=?", "f".repeat(64)));
        assertThrows(org.springframework.dao.DataAccessException.class, () -> jdbc.execute("DELETE FROM arte_ai_tool_schema"));
        assertEquals(2, count("schema"));
    }

    @Test
    public void forgedChecksumsAndUnsupportedSchemasAreRejectedBeforeWriting() {
        var batch = batch("v1");
        var schema = batch.schemas().getFirst();
        var forged = new ToolSchemaDefinition(schema.definition(), schema.document(), "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> first.put(new ToolCatalogStore.Batch(batch.providers(), List.of(forged), List.of())).block(WAIT));
        var badSchema = schema(ref("schema", "bad", "v1"), object("format", text("email")));
        assertThrows(ToolSchemaValidator.InvalidSchemaException.class, () -> first.put(new ToolCatalogStore.Batch(List.of(), List.of(badSchema), List.of())).block(WAIT));
        var t = batch.tools().getFirst().tool();
        var badTool = new ToolDefinition(t.definition(), t.providerId(), t.title(), t.description(), t.tags(), t.capability(),
                t.inputSchema(), t.outputSchema(), t.configurationSchema(), t.defaultConfiguration(), t.capabilities(), t.risk(), t.defaultPolicy(), "0".repeat(64));
        assertThrows(IllegalArgumentException.class, () -> first.put(new ToolCatalogStore.Batch(batch.providers(), batch.schemas(),
                List.of(new ToolDefinitionRevision(badTool, provider("v1").definition(), batch.tools().getFirst().target())))).block(WAIT));
        assertEquals(0, count("provider"));
    }

    @Test
    public void inputAndConfigurationSchemasMustHaveObjectRootsAndDefaultsMustMatch() {
        var batch = batch("v1");
        var wrong = schema(batch.schemas().getFirst().definition(), object("type", text("integer")));
        assertThrows(IllegalArgumentException.class, () -> first.put(new ToolCatalogStore.Batch(batch.providers(),
                List.of(wrong, batch.schemas().getLast()), batch.tools())).block(WAIT));
        var invalidDefault = revise(batch.tools().getFirst(), "counter", object("unknown", text("value")));
        assertThrows(IllegalArgumentException.class, () -> first.put(new ToolCatalogStore.Batch(batch.providers(), batch.schemas(), List.of(invalidDefault))).block(WAIT));
        assertEquals(0, count("provider"));
        assertEquals(0, count("schema"));
    }

    @Test
    public void targetKindAndFixedConnectionMustMatchTheProvider() {
        var batch = batch("v1");
        var tool = batch.tools().getFirst();
        var remote = new ToolDefinitionRevision(tool.tool(), tool.provider(), new ExecutionTarget.Remote(ref("connection", "http", "v1"), "get"));
        assertEquals(INVALID_STATE, first.put(new ToolCatalogStore.Batch(batch.providers(), batch.schemas(), List.of(remote))).block(WAIT).code());
        var provider = new ToolProviderDefinition(tool.provider(), ToolProviderDefinition.Kind.HTTP,
                ref("tool-provider-adapter", "http", "v1"), ref("connection", "different", "v1"));
        assertEquals(INVALID_STATE, first.put(new ToolCatalogStore.Batch(List.of(provider), batch.schemas(), List.of(remote))).block(WAIT).code());
        assertEquals(0, count("definition"));
    }

    @Test
    public void aRemoteDefinitionCanBeStoredWithoutExecutingOrResolvingItsConnection() {
        var batch = batch("v1");
        var old = batch.tools().getFirst();
        var connection = ref("connection", "http", "v1");
        var provider = new ToolProviderDefinition(old.provider(), ToolProviderDefinition.Kind.HTTP, ref("tool-provider-adapter", "http", "v1"), connection);
        var tool = new ToolDefinitionRevision(old.tool(), old.provider(), new ExecutionTarget.Remote(connection, "lookup"));
        assertEquals(APPLIED, first.put(new ToolCatalogStore.Batch(List.of(provider), batch.schemas(), List.of(tool))).block(WAIT).code());
        assertEquals(tool, second.findTool(tool.tool().definition()).block(WAIT));
    }

    @Test
    public void corruptedPayloadAndIndexFactsFailInsteadOfLookingMissing() {
        var batch = batch("v1");
        first.put(batch).block(WAIT);
        jdbc.update("UPDATE arte_ai_tool_definition SET payload_digest=?", "0".repeat(64));
        assertThrows(IllegalStateException.class, () -> first.findTool(batch.tools().getFirst().tool().definition()).block(WAIT));
        jdbc.update("UPDATE arte_ai_tool_provider SET identity_key=?", "0".repeat(64));
        assertThrows(IllegalStateException.class, () -> first.findProvider(provider("v1").definition()).block(WAIT));
    }

    @Test
    public void rehashedButSubstitutedSnapshotIsRejectedByItsFixedIdentity() {
        var batch = batch("v1");
        first.put(batch).block(WAIT);
        var other = provider("v2");
        String payload = CanonicalJson.normalize(codec.encode(other));
        jdbc.update("UPDATE arte_ai_tool_provider SET workspace_snapshot=?,payload_digest=?", payload, CanonicalJson.sha256(payload));
        assertThrows(IllegalStateException.class, () -> first.findProvider(provider("v1").definition()).block(WAIT));
    }

    @Test
    public void providerPagesAreBoundedAndExhaustWithoutGuessingVersionOrder() {
        for (int i = 0; i < 5; i++)
            first.put(new ToolCatalogStore.Batch(List.of(provider("v" + i)), List.of(), List.of())).block(WAIT);
        var seen = new HashSet<DefinitionRef>();
        String cursor = null;
        do {
            var page = second.listProviders(cursor, 2).block(WAIT);
            assertTrue(page.items().size() <= 2);
            for (var item : page.items()) assertTrue(seen.add(item.definition()));
            cursor = page.nextCursor();
        } while (cursor != null);
        assertEquals(5, seen.size());
        assertThrows(IllegalArgumentException.class, () -> first.listProviders("bad cursor", 10));
        assertThrows(IllegalArgumentException.class, () -> first.listProviders(null, 101));
        assertThrows(IllegalArgumentException.class, () -> first.listProviders(null, 0));
    }

    @Test
    public void toolPagesKeepProviderRevisionsIsolated() {
        first.put(batch("v1")).block(WAIT);
        first.put(batch("v2")).block(WAIT);
        var page = second.listTools(provider("v1").definition(), null, 1).block(WAIT);
        assertEquals(1, page.items().size());
        assertEquals(provider("v1").definition(), page.items().getFirst().provider());
        assertNull(page.nextCursor());
    }

    @Test
    public void missingReferencesAreEmptyButInfrastructureFailuresPropagate() {
        assertNull(first.findProvider(provider("missing").definition()).block(WAIT));
        assertNull(first.findSchema(ref("schema", "missing", "v1")).block(WAIT));
        jdbc.execute("DROP TABLE arte_ai_tool_definition");
        assertThrows(org.springframework.dao.DataAccessException.class, () -> first.findTool(ref("tool", "text.count", "v1")).block(WAIT));
    }

    @Test
    public void declaredDigestExcludesItselfAndCanonicalSchemaPreservesArrayOrder() {
        var original = batch("v1").tools().getFirst().tool();
        assertEquals(original.checksum(), ToolCatalogDigests.tool(original));
        var one = object("enum", new ArrayValue(List.of(text("a"), text("b"))));
        var reversed = object("enum", new ArrayValue(List.of(text("b"), text("a"))));
        assertNotEquals(ToolCatalogDigests.schema(one), ToolCatalogDigests.schema(reversed));
        var nativeJson = JsonMapper.builder().build().readValue("{\"type\":\"object\",\"$schema\":\"urn:arte:ai-tool:schema:v1\"}", Object.class);
        assertEquals(CanonicalJson.digest(nativeJson), ToolCatalogDigests.schema(batch("v1").schemas().getFirst().document()));
    }

    @Test
    public void newSnapshotAliasesAndTargetTypesAreClosedAndVersioned() {
        var tool = batch("v1").tools().getFirst();
        String encoded = codec.encode(tool);
        assertEquals(tool, codec.decode(encoded, ToolDefinitionRevision.class));
        assertThrows(RuntimeException.class, () -> codec.decode(encoded.replace("target-local", "java.lang.Runtime"), ToolDefinitionRevision.class));
        assertThrows(RuntimeException.class, () -> codec.decode(encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":99"), ToolDefinitionRevision.class));
        assertThrows(RuntimeException.class, () -> codec.decode(encoded, ToolProviderDefinition.class));
    }

    @Test
    public void duplicateBatchIdentitiesAndWrongReferenceKindsAreRejected() {
        var batch = batch("v1");
        assertThrows(IllegalArgumentException.class, () -> new ToolCatalogStore.Batch(List.of(provider("v1"), provider("v1")), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ToolCatalogStore.Batch(List.of(), List.of(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> new ToolProviderDefinition(ref("provider", "local", "v1"), ToolProviderDefinition.Kind.LOCAL,
                ref("tool-provider-adapter", "local", "v1"), null));
        assertThrows(IllegalArgumentException.class, () -> new ToolProviderDefinition(provider("v1").definition(), ToolProviderDefinition.Kind.LOCAL,
                provider("v1").adapter(), ref("connection", "bad", "v1")));
        assertThrows(IllegalArgumentException.class, () -> first.findSchema(batch.tools().getFirst().tool().definition()));
        assertThrows(IllegalArgumentException.class, () -> new ToolDefinitionRevision(batch.tools().getFirst().tool(),
                ref("tool-provider", "other", "v1"), batch.tools().getFirst().target()));
    }

    @Test
    public void utf8RecordAndTotalBatchLimitsApplyBeforeDatabaseWrites() {
        var batch = batch("v1");
        var oversized = revise(batch.tools().getFirst(), "counter", object("payload", text("中".repeat(400_000))));
        assertThrows(IllegalArgumentException.class, () -> first.put(new ToolCatalogStore.Batch(batch.providers(), batch.schemas(), List.of(oversized))).block(WAIT));
        var tools = new ArrayList<ToolDefinitionRevision>();
        for (int i = 0; i < 15; i++)
            tools.add(revise(batch("version" + i).tools().getFirst(), "counter", object("payload", text("x".repeat(600_000)))));
        var error = assertThrows(IllegalArgumentException.class, () -> first.put(new ToolCatalogStore.Batch(List.of(), List.of(), tools)).block(WAIT));
        assertTrue(error.getMessage().contains("batch exceeds"));
        assertEquals(0, count("definition"));
    }

    @Test
    public void baselineAndIncrementalCreateStatementsStayIdentical() throws Exception {
        String baseline = Files.readString(Path.of("scripts/arte-ai-new-ddl-mysql.sql"));
        String incremental = Files.readString(Path.of("scripts/arte-ai-tool-catalog-migration.sql"));
        assertEquals(incremental.substring(incremental.indexOf("create table ")).trim(),
                baseline.substring(baseline.indexOf("create table arte_ai_tool_provider")).trim());
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_tool_" + table, Integer.class);
    }

    private static ToolProviderDefinition provider(String version) {
        return new ToolProviderDefinition(ref("tool-provider", "local", version), ToolProviderDefinition.Kind.LOCAL,
                ref("tool-provider-adapter", "local", "v1"), null);
    }

    private static ToolCatalogStore.Batch batch(String version) {
        var provider = provider(version);
        var object = schema(ref("schema", "object", version), object("type", text("object")));
        var output = schema(ref("schema", "output", version), object("type", text("integer")));
        var unsigned = new ToolDefinition(ref("tool", "text.count", version), provider.definition().id(), "counter", "Count text",
                Set.of("text"), ref("capability", "count", version), object.definition(), output.definition(), object.definition(),
                object(), new ToolCapabilities(Set.of(), false),
                new ToolRiskProfile(CapabilityDescriptor.SideEffect.READ_ONLY, false, ToolRiskProfile.Idempotency.NONE, Set.of(), Set.of()),
                new ToolExecutionPolicy(Duration.ofSeconds(30), 1, Duration.ZERO, 1024, 1024, ToolExecutionPolicy.ApprovalMode.NONE), "0".repeat(64));
        var revision = new ToolDefinitionRevision(seal(unsigned), provider.definition(),
                new ExecutionTarget.Local(ref("tool-adapter", "counter", "v1")));
        return new ToolCatalogStore.Batch(List.of(provider), List.of(object, output), List.of(revision));
    }

    private static ToolDefinitionRevision revise(ToolDefinitionRevision old, String title, ObjectValue configuration) {
        var t = old.tool();
        var unsigned = new ToolDefinition(t.definition(), t.providerId(), title, t.description(), t.tags(), t.capability(),
                t.inputSchema(), t.outputSchema(), t.configurationSchema(), configuration, t.capabilities(), t.risk(), t.defaultPolicy(), t.checksum());
        return new ToolDefinitionRevision(seal(unsigned), old.provider(), old.target());
    }

    private static ToolDefinition seal(ToolDefinition t) {
        return new ToolDefinition(t.definition(), t.providerId(), t.title(), t.description(), t.tags(), t.capability(),
                t.inputSchema(), t.outputSchema(), t.configurationSchema(), t.defaultConfiguration(), t.capabilities(), t.risk(), t.defaultPolicy(), ToolCatalogDigests.tool(t));
    }

    private static ToolSchemaDefinition schema(DefinitionRef ref, ObjectValue body) {
        var fields = new HashMap<>(body.fields());
        fields.put("$schema", text(ToolSchemaValidator.DIALECT));
        var document = new ObjectValue(fields);
        return new ToolSchemaDefinition(ref, document, ToolCatalogDigests.schema(document));
    }

    private static DefinitionRef ref(String type, String id, String version) {
        return new DefinitionRef(type, id, version);
    }

    private static StringValue text(String text) {
        return new StringValue(text);
    }

    private static ObjectValue object(Object... pairs) {
        var fields = new HashMap<String, StructuredValue>();
        for (int i = 0; i < pairs.length; i += 2) fields.put((String) pairs[i], (StructuredValue) pairs[i + 1]);
        return new ObjectValue(fields);
    }
}
