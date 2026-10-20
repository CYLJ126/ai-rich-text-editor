package com.arte.ainew.tool.chat;

import com.arte.ainew.common.execution.*;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.persistence.mybatis.MybatisToolChatRunStore;
import com.arte.ainew.pojo.context.ContextBudget;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationOptions;
import com.arte.ainew.pojo.generation.ToolCall;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.serialization.ToolChatRunDigests;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.arte.ainew.pojo.execution.StoreOutcome.Code.*;
import static com.arte.ainew.pojo.tool.ToolChatRun.Phase.*;
import static com.arte.ainew.tool.discovery.LocalToolFixtures.*;
import static org.junit.Assert.*;

/**
 * 真实 MyBatis／同库事务／隔离 H2；无模型、工具、业务数据源或后台 Worker。
 */
public class ToolChatRunStoreTest {
    private static final Duration WAIT = Duration.ofSeconds(15);
    private static final Duration LEASE = Duration.ofSeconds(10);

    private static final class Rig implements AutoCloseable {
        final JdbcDataSource source = new JdbcDataSource();
        final reactor.core.scheduler.Scheduler io = Schedulers.newBoundedElastic(8, 64, "tool-chat-db-test");
        final JdbcTemplate jdbc;
        final JacksonExecutionRecordCodec codec = new JacksonExecutionRecordCodec();
        final MybatisToolChatRunStore store;
        final ExecutionOwner owner = new ExecutionOwner("tenant", "workspace", "subject");
        final AtomicBoolean allowed = new AtomicBoolean(true);
        final Fixture fixture = fixture("text", "v1");

        Rig() {
            source.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=5000");
            jdbc = new JdbcTemplate(source);
            new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).execute(source);
            store = restart();
        }

        MybatisToolChatRunStore restart() {
            return new MybatisToolChatRunStore(source, codec, io);
        }

        void authorize() {
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
            assertTrue(TransactionSynchronizationManager.hasResource(source));
            if (!allowed.get()) throw new AccessDeniedException("Test qualification revoked");
        }

        ToolChatRunSnapshot input(String runId, String key) {
            return input(runId, key, 3, 5);
        }

        ToolChatRunSnapshot input(String runId, String key, int rounds, int calls) {
            Instant received = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
            var auth = new ExecutionAuthorization(new ExecutionPrincipal("subject", "user", ExecutionPrincipal.Kind.USER), "tenant", "workspace", Set.of("ai:invoke"), "grant");
            var context = new ExecutionContext(runId, "trace", auth, received.plusSeconds(60), null, null, "release", key);
            var capability = new CapabilityDescriptor(ref("capability", "generation", "v1"), CapabilityDescriptor.Kind.GENERATION,
                    ref("schema", "generation-request", "v1"), ref("schema", "model-result", "v1"),
                    Set.of(CapabilityDescriptor.Feature.TEXT_INPUT, CapabilityDescriptor.Feature.STREAMING, CapabilityDescriptor.Feature.TOOL_CALLS),
                    CapabilityDescriptor.SideEffect.READ_ONLY, CapabilityDescriptor.Availability.EXECUTABLE);
            var model = new ResolvedBinding(ref("binding", "generation", "v1"), capability, ref("connection", "model", "v1"), "model", 8192L, ref("rate", "model", "v1"));
            var schema = schema(fixture.schema().definition(), object("$schema", text("urn:arte:ai-tool:schema:v1"), "type", text("object"),
                    "properties", object("text", object("type", text("string")), "amount", object("type", text("number")))));
            var config = object("text", text("fixed config"));
            var binding = new ToolBindingResolution(ref("binding", "selected", "p1-" + "1".repeat(64)), ref("binding", "selected", "r1"), owner,
                    fixture.revision(), fixture.revision().tool().checksum(), config, ToolCatalogDigests.configuration(config), fixture.revision().tool().defaultPolicy(), Set.of(), Map.of());
            var tools = new ToolSetSnapshot(owner, List.of(new ToolSetSnapshot.Entry("tool_0", binding, binding.effectivePolicy(), schema)));
            var initial = new ContextSnapshot("context-" + runId, null, model.definition(), List.of(new ChatMessage("user", ChatMessage.Role.USER,
                    List.of(new ChatMessage.Text("read text")), List.of(), null)), List.of(), new ContextBudget(8192, 4096, 1024, 1024),
                    10, true, "test-tokenizer", List.of(), "0".repeat(64), received, received.plusSeconds(120));
            return new ToolChatRunSnapshot(context, initial, model, new GenerationOptions(128, null, null, List.of()), tools,
                    new ToolChatRunSnapshot.Limits(Duration.ofSeconds(60), rounds, calls, 1), received);
        }

        ToolChatRun accept(ToolChatRunSnapshot input) {
            return store.accept(input, ignored -> authorize()).block(WAIT).value();
        }

        ToolChatCommands.Lease claim() {
            return store.claim("worker", 1, LEASE).block(WAIT).getFirst();
        }

        ToolChatCommands.Advance model(ToolChatRunSnapshot s, ToolChatCommands.Lease lease, String key) {
            return new ToolChatCommands.Advance(lease, 0, key, ToolChatRun.State.RUNNING, WAITING_MODEL, 0,
                    List.of(new ToolChatStep.Plan(0, new ToolChatStep.Model(s.initialContext()))), null, null, null);
        }

        StoreOutcome<ToolChatCommands.Receipt> advance(ToolChatCommands.Advance command) {
            return store.advance(command, ignored -> authorize()).block(WAIT);
        }

        ToolChatCommands.Advance tools(ToolChatCommands.Lease lease, long version, String key, String... callIds) {
            return new ToolChatCommands.Advance(lease, version, key, ToolChatRun.State.WAITING_TOOL, WAITING_TOOLS, 0,
                    Arrays.stream(callIds).map(id -> new ToolChatStep.Plan(0, new ToolChatStep.Tool(new ToolCall(id, fixture.revision().tool().definition(),
                            object("amount", new com.arte.ainew.common.value.StructuredValue.NumberValue(new BigDecimal("0.12345678901234567890123456789"))))))).toList(), null, null, null);
        }

        void wake() {
            assertTrue(store.wake(owner, "run").block(WAIT).successful());
        }

        int count(String table) {
            return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        }

        ToolChatRun run() {
            return store.find(owner, "run").block(WAIT);
        }

        void expireLease() {
            jdbc.update("UPDATE arte_ai_tool_chat_run_work SET lease_until=0");
        }

        @Override
        public void close() {
            io.dispose();
            jdbc.execute("DROP ALL OBJECTS");
        }
    }

    @Test
    public void acceptanceIsAtomicAndReplayKeepsOriginalIdentityDeadlineAndFrozenInput() {
        try (var r = new Rig()) {
            var first = r.input("run", "key");
            var accepted = r.accept(first);
            var retried = r.input("new-id", "key");
            assertEquals(REPLAYED, r.store.accept(retried, ignored -> r.authorize()).block(WAIT).code());
            assertEquals(accepted, r.run());
            assertEquals(first, r.restart().snapshot(r.owner, "run").block(WAIT));
            assertEquals(1, r.count("arte_ai_tool_chat_run_work"));
            assertEquals(0, r.count("arte_ai_invocation"));
            assertEquals(0, r.count("arte_ai_tool_chat_run_step"));
            var conflict = r.input("other", "key", 2, 5);
            assertEquals(IDEMPOTENCY_CONFLICT, r.store.accept(conflict, ignored -> r.authorize()).block(WAIT).code());
            r.allowed.set(false);
            assertThrows(AccessDeniedException.class, () -> r.store.accept(retried, ignored -> r.authorize()).block(WAIT));
        }
    }

    @Test
    public void concurrentAcceptanceAndAdvanceCreateOnlyOneLogicalChildAndReplayOriginalReceipt() {
        try (var r = new Rig()) {
            var input = r.input("run", "key");
            var accepted = Flux.range(0, 8).flatMap(i -> r.store.accept(input, ignored -> r.authorize()), 8).collectList().block(WAIT);
            assertEquals(1, accepted.stream().filter(o -> o.code() == APPLIED).count());
            assertEquals(7, accepted.stream().filter(o -> o.code() == REPLAYED).count());
            var command = r.model(input, r.claim(), "model-0");
            var outcomes = Flux.range(0, 8).flatMap(i -> r.store.advance(command, ignored -> r.authorize()), 8).collectList().block(WAIT);
            assertEquals(1, outcomes.stream().filter(o -> o.code() == APPLIED).count());
            assertEquals(7, outcomes.stream().filter(o -> o.code() == REPLAYED).count());
            var original = outcomes.getFirst().value();
            assertEquals(1, r.count("arte_ai_tool_chat_run_step"));
            assertEquals(1, r.count("arte_ai_tool_chat_run_command"));
            assertEquals(1, r.run().version());
            assertEquals(original, r.restart().advance(command, ignored -> r.authorize()).block(WAIT).value());
            assertEquals(ToolChatStep.childId("run", command.plans().getFirst()), original.steps().getFirst().childExecutionId());
        }
    }

    @Test
    public void restoredMappingsPreserveParametersAndNoReplayFollowsCurrentToolConfiguration() {
        try (var r = new Rig()) {
            var input = r.input("run", "key");
            r.accept(input);
            var model = r.model(input, r.claim(), "model-0");
            r.advance(model);
            r.wake();
            var tools = r.tools(r.claim(), 1, "tools-0", "call-1", "call-2");
            var original = r.advance(tools).value();
            var restored = r.restart();
            assertEquals(original, restored.advance(tools, ignored -> r.authorize()).block(WAIT).value());
            assertEquals(3, restored.steps(r.owner, "run").block(WAIT).size());
            assertEquals(2, r.run().toolSteps());
            assertEquals(input.tools(), restored.snapshot(r.owner, "run").block(WAIT).tools());
            var number = ((ToolChatStep.Tool) original.steps().getFirst().input()).call().arguments().fields().get("amount");
            assertEquals(new BigDecimal("0.12345678901234567890123456789"), ((com.arte.ainew.common.value.StructuredValue.NumberValue) number).value());
            assertEquals(0, r.count("arte_ai_invocation"));
        }
    }

    @Test
    public void newWorkerReclaimsExpiredLeaseAndOldWorkerCannotRenewOrAdvance() {
        try (var r = new Rig()) {
            var input = r.input("run", "key");
            r.accept(input);
            var old = r.claim();
            assertTrue(r.store.claim("other", 1, LEASE).block(WAIT).isEmpty());
            r.expireLease();
            var next = r.store.claim("other", 1, LEASE).block(WAIT).getFirst();
            assertEquals(old.fence() + 1, next.fence());
            assertEquals(LEASE_LOST, r.store.renew(old, LEASE).block(WAIT).code());
            assertEquals(LEASE_LOST, r.advance(r.model(input, old, "old-command")).code());
            assertEquals(APPLIED, r.advance(r.model(input, next, "model-0")).code());
            assertEquals(REPLAYED, r.advance(r.model(input, old, "model-0")).code());
            assertEquals(1, r.count("arte_ai_tool_chat_run_step"));
        }
    }

    @Test
    public void concurrentWorkersClaimEachRunOnlyOnceAndRenewDoesNotChangeItsDeadline() {
        try (var r = new Rig()) {
            for (int i = 0; i < 4; i++) r.accept(r.input("run-" + i, "key-" + i));
            var leases = Flux.range(0, 8).flatMap(i -> r.store.claim("worker-" + i, 4, LEASE), 8).flatMapIterable(list -> list).collectList().block(WAIT);
            assertEquals(4, leases.size());
            assertEquals(4, new HashSet<>(leases.stream().map(ToolChatCommands.Lease::runId).toList()).size());
            var lease = leases.getFirst();
            var before = r.store.snapshot(r.owner, lease.runId()).block(WAIT).context().deadline();
            assertEquals(APPLIED, r.store.renew(lease, LEASE).block(WAIT).code());
            assertEquals(before, r.store.snapshot(r.owner, lease.runId()).block(WAIT).context().deadline());
        }
    }

    @Test
    public void expiredRunMayOnlyCloseAndCannotPlanAnotherChild() {
        try (var r = new Rig()) {
            var original = r.input("run", "key");
            var accepted = r.accept(original);
            // 构造两分钟前已受理的记录，避免以 wall-clock sleep 模拟恢复；保留全部快照摘要约束。
            Duration age = Duration.ofMinutes(2);
            var c = original.context();
            var old = new ExecutionContext(c.executionId(), c.traceId(), c.authorization(), c.deadline().minus(age), null, null, c.releaseRef(), c.idempotencyKey());
            var ctx = original.initialContext();
            var oldContext = new ContextSnapshot(ctx.snapshotId(), ctx.history(), ctx.modelBinding(), ctx.messages(), ctx.fragments(), ctx.budget(), ctx.inputTokens(),
                    ctx.estimatedTokens(), ctx.tokenizerVersion(), ctx.truncations(), ctx.contentDigest(), ctx.createdAt().minus(age), ctx.expiresAt().minus(age));
            var expired = new ToolChatRunSnapshot(old, oldContext, original.model(), original.generation(), original.tools(), original.limits(), original.receivedAt().minus(age));
            String inputJson = r.codec.encode(expired);
            var oldRun = new ToolChatRun(accepted.runId(), accepted.owner(), accepted.state(), accepted.version(), accepted.checkpoint(), 0, 0, null, null, accepted.acceptedAt().minus(age), accepted.updatedAt().minus(age));
            String runJson = r.codec.encode(oldRun);
            r.jdbc.update("UPDATE arte_ai_tool_chat_run SET input_snapshot=?,input_digest=?,deadline=?,state_snapshot=?,state_digest=?", inputJson, CanonicalJson.sha256(inputJson), old.deadline().toEpochMilli(), runJson, CanonicalJson.sha256(runJson));
            r.jdbc.update("UPDATE arte_ai_tool_chat_run_work SET available_at=?", old.deadline().toEpochMilli());
            var lease = r.claim();
            assertEquals(INVALID_STATE, r.advance(r.model(expired, lease, "late-model")).code());
            var error = new ExecutionError("RUN_TIMED_OUT", ExecutionError.Phase.INVOCATION, false, ExecutionError.SideEffect.NONE, ExecutionError.Certainty.KNOWN, "trace");
            var timeout = new ToolChatCommands.Advance(lease, 0, "timeout", ToolChatRun.State.TIMED_OUT, FINISHED, 0, List.of(), null, null, error);
            assertEquals(APPLIED, r.advance(timeout).code());
            assertEquals(0, r.count("arte_ai_tool_chat_run_step"));
            assertTrue(r.store.claim("other", 1, LEASE).block(WAIT).isEmpty());
        }
    }

    @Test
    public void wakeDuringLeaseCannotBeLostByParkAndDuplicateNotificationsNeverCreateSteps() {
        try (var r = new Rig()) {
            var input = r.input("run", "key");
            r.accept(input);
            var lease = r.claim();
            r.wake();
            r.wake();
            r.advance(r.model(input, lease, "model-0"));
            var next = r.claim();
            assertEquals(2, next.signalVersion());
            var park = new ToolChatCommands.Advance(next, 1, "park", ToolChatRun.State.RUNNING, WAITING_MODEL, 0, List.of(), null, null, null);
            assertEquals(APPLIED, r.advance(park).code());
            assertTrue(r.store.claim("worker", 1, LEASE).block(WAIT).isEmpty());
            assertEquals(input.context().deadline().toEpochMilli(), r.jdbc.queryForObject("SELECT available_at FROM arte_ai_tool_chat_run_work", Long.class).longValue());
            assertEquals(1, r.count("arte_ai_tool_chat_run_step"));
        }
    }

    @Test
    public void versionAndCommandConflictsDoNotChangeCheckpointOrWork() {
        try (var r = new Rig()) {
            var input = r.input("run", "key");
            r.accept(input);
            var first = r.model(input, r.claim(), "same");
            r.advance(first);
            r.wake();
            var lease = r.claim();
            assertEquals(IDEMPOTENCY_CONFLICT, r.advance(r.tools(lease, 1, "same", "call")).code());
            assertEquals(VERSION_CONFLICT, r.advance(r.tools(lease, 0, "wrong-version", "call")).code());
            assertEquals(1, r.run().version());
            assertEquals(1, r.count("arte_ai_tool_chat_run_command"));
            assertEquals(1, r.count("arte_ai_tool_chat_run_step"));
            assertEquals(APPLIED, r.advance(r.tools(lease, 1, "tools", "call")).code());
        }
    }

    @Test
    public void duplicateCallIdsInvalidSchemaAndRoundLimitsRejectTheWholePlan() {
        try (var r = new Rig()) {
            var input = r.input("run", "key", 1, 1);
            r.accept(input);
            r.advance(r.model(input, r.claim(), "model-0"));
            r.wake();
            var lease = r.claim();
            assertEquals(IDEMPOTENCY_CONFLICT, r.advance(r.tools(lease, 1, "duplicate", "same", "same")).code());
            assertEquals(INVALID_STATE, r.advance(r.tools(lease, 1, "too-many", "a", "b")).code());
            var invalid = new ToolChatCommands.Advance(lease, 1, "invalid", ToolChatRun.State.WAITING_TOOL, WAITING_TOOLS, 0,
                    List.of(new ToolChatStep.Plan(0, new ToolChatStep.Tool(new ToolCall("call", r.fixture.revision().tool().definition(), object("text", new com.arte.ainew.common.value.StructuredValue.NumberValue(BigDecimal.ONE)))))), null, null, null);
            assertEquals(INVALID_STATE, r.advance(invalid).code());
            var large = object("text", text("a".repeat(600_000)));
            assertThrows(IllegalArgumentException.class, () -> new ToolChatCommands.Advance(lease, 1, "oversized-batch", ToolChatRun.State.WAITING_TOOL, WAITING_TOOLS, 0,
                    List.of(new ToolChatStep.Plan(0, new ToolChatStep.Tool(new ToolCall("large-1", r.fixture.revision().tool().definition(), large))),
                            new ToolChatStep.Plan(0, new ToolChatStep.Tool(new ToolCall("large-2", r.fixture.revision().tool().definition(), large)))), null, null, null));
            assertEquals(1, r.count("arte_ai_tool_chat_run_step"));
            assertEquals(APPLIED, r.advance(r.tools(lease, 1, "tools", "call")).code());
            r.wake();
            assertEquals(INVALID_STATE, r.advance(new ToolChatCommands.Advance(r.claim(), 2, "round-1", ToolChatRun.State.RUNNING, READY_MODEL, 1, List.of(), null, null, null)).code());
            assertEquals(2, r.run().version());
        }
    }

    @Test
    public void rollbackAtFinalReceiptWriteRestoresStepsCheckpointCountersAndLease() {
        try (var r = new Rig()) {
            var input = r.input("run", "key");
            r.accept(input);
            var lease = r.claim();
            var command = r.model(input, lease, "model-0");
            r.jdbc.execute("ALTER TABLE arte_ai_tool_chat_run_command ADD CONSTRAINT test_fault CHECK (command_key='never')");
            assertThrows(RuntimeException.class, () -> r.advance(command));
            assertEquals(0, r.run().version());
            assertEquals(0, r.run().modelSteps());
            assertEquals(0, r.count("arte_ai_tool_chat_run_step"));
            assertEquals(0, r.count("arte_ai_tool_chat_run_command"));
            assertEquals(lease.fence(), r.jdbc.queryForObject("SELECT fence FROM arte_ai_tool_chat_run_work", Long.class).longValue());
            assertEquals("worker", r.jdbc.queryForObject("SELECT worker_id FROM arte_ai_tool_chat_run_work", String.class));
            r.jdbc.execute("ALTER TABLE arte_ai_tool_chat_run_command DROP CONSTRAINT test_fault");
            assertEquals(APPLIED, r.advance(command).code());
        }
    }

    @Test
    public void rollbackWhenFirstWorkItemCannotBeWrittenLeavesNoAcceptedRun() {
        try (var r = new Rig()) {
            r.jdbc.execute("ALTER TABLE arte_ai_tool_chat_run_work ADD CONSTRAINT test_fault CHECK (fence > 0)");
            assertThrows(RuntimeException.class, () -> r.accept(r.input("run", "key")));
            assertEquals(0, r.count("arte_ai_tool_chat_run"));
            assertEquals(0, r.count("arte_ai_tool_chat_run_work"));
            r.jdbc.execute("ALTER TABLE arte_ai_tool_chat_run_work DROP CONSTRAINT test_fault");
            assertNotNull(r.accept(r.input("run", "key")));
        }
    }

    @Test
    public void revokedAuthorizationAndForeignOwnersCannotAdvanceReadOrWake() {
        try (var r = new Rig()) {
            var input = r.input("run", "key");
            r.accept(input);
            var command = r.model(input, r.claim(), "model-0");
            r.allowed.set(false);
            assertThrows(AccessDeniedException.class, () -> r.advance(command));
            assertEquals(0, r.run().version());
            r.allowed.set(true);
            r.advance(command);
            r.allowed.set(false);
            assertThrows(AccessDeniedException.class, () -> r.advance(command));
            var foreign = new ExecutionOwner("tenant", "elsewhere", "subject");
            assertNull(r.store.find(foreign, "run").block(WAIT));
            assertNull(r.store.snapshot(foreign, "run").block(WAIT));
            assertTrue(r.store.steps(foreign, "run").block(WAIT).isEmpty());
            assertEquals(NOT_FOUND, r.store.wake(foreign, "run").block(WAIT).code());
        }
    }

    @Test
    public void terminalCheckpointStopsWorkAndCannotBeReopened() {
        try (var r = new Rig()) {
            var input = r.input("run", "key");
            r.accept(input);
            var lease = r.claim();
            var cancel = new ToolChatCommands.Advance(lease, 0, "cancel", ToolChatRun.State.CANCELLED, FINISHED, 0, List.of(), null, null, null);
            assertEquals(APPLIED, r.advance(cancel).code());
            assertEquals(REPLAYED, r.store.wake(r.owner, "run").block(WAIT).code());
            assertTrue(r.store.claim("worker", 1, LEASE).block(WAIT).isEmpty());
            assertEquals(LEASE_LOST, r.advance(r.model(input, lease, "new")).code());
            assertEquals(REPLAYED, r.advance(cancel).code());
        }
    }

    @Test
    public void approvalAndUnknownWaitKeepTheSameChildrenAcrossRestartAndRoundProgression() {
        try (var r = new Rig()) {
            var input = r.input("run", "key");
            r.accept(input);
            r.advance(r.model(input, r.claim(), "model-0"));
            r.wake();
            var tools = r.advance(r.tools(r.claim(), 1, "tools-0", "call-1")).value();
            r.wake();
            assertEquals(APPLIED, r.advance(new ToolChatCommands.Advance(r.claim(), 2, "approval", ToolChatRun.State.WAITING_APPROVAL, WAITING_APPROVAL, 0, List.of(), null, null, null)).code());
            r.wake();
            var unknown = new ExecutionError("BUSINESS_OUTCOME_UNKNOWN", ExecutionError.Phase.INVOCATION, false, ExecutionError.SideEffect.POSSIBLE, ExecutionError.Certainty.UNKNOWN, "trace");
            r.advance(new ToolChatCommands.Advance(r.claim(), 3, "unknown", ToolChatRun.State.PENDING_RECONCILIATION, RECONCILING, 0, List.of(), null, null, unknown));
            assertEquals(tools.run().checkpoint().children(), r.restart().find(r.owner, "run").block(WAIT).checkpoint().children());
            r.wake();
            var lease = r.claim();
            assertEquals(INVALID_STATE, r.advance(new ToolChatCommands.Advance(lease, 4, "wrong-kind", ToolChatRun.State.RUNNING, WAITING_MODEL, 0, List.of(), null, null, null)).code());
            assertEquals(APPLIED, r.advance(new ToolChatCommands.Advance(lease, 4, "continue", ToolChatRun.State.RUNNING, READY_MODEL, 1, List.of(), null, null, null)).code());
            r.wake();
            var next = new ToolChatCommands.Advance(r.claim(), 5, "model-1", ToolChatRun.State.RUNNING, WAITING_MODEL, 1,
                    List.of(new ToolChatStep.Plan(1, new ToolChatStep.Model(input.initialContext()))), null, null, null);
            var result = r.advance(next).value();
            assertNotEquals(ToolChatStep.childId("run", new ToolChatStep.Plan(0, new ToolChatStep.Model(input.initialContext()))), result.steps().getFirst().childExecutionId());
            assertEquals(2, result.run().modelSteps());
            assertEquals(1, result.run().toolSteps());
            assertEquals(3, r.store.steps(r.owner, "run").block(WAIT).size());
        }
    }

    @Test
    public void corruptedPersistedInputAndUnregisteredCodecVersionsFailClosed() {
        try (var r = new Rig()) {
            var input = r.input("run", "key");
            r.accept(input);
            String encoded = r.codec.encode(input);
            for (String version : List.of("2", "1.5", "\"1\""))
                assertThrows(IllegalArgumentException.class, () -> r.codec.decode(encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":" + version), ToolChatRunSnapshot.class));
            r.jdbc.update("UPDATE arte_ai_tool_chat_run SET input_snapshot=?", encoded.replace("fixed config", "tampered"));
            assertThrows(IllegalStateException.class, () -> r.store.snapshot(r.owner, "run").block(WAIT));
        }
    }

    @Test
    public void semanticDigestIsStableAcrossIndependentJvmProcesses() throws Exception {
        String expected;
        try (var r = new Rig()) {
            expected = ToolChatRunDigests.submission(r.input("run", "key"));
        }
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path arguments = Files.createTempFile("arte-tool-chat-jvm-", ".args");
        try {
            Files.writeString(arguments, "-cp\n\"" + classpath.replace("\\", "\\\\").replace("\"", "\\\"") + "\"\n" + DigestProbe.class.getName() + "\n");
            for (int i = 0; i < 3; i++) {
                var process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "@" + arguments).redirectErrorStream(true).start();
                try {
                    assertTrue("Digest probe timed out", process.waitFor(30, TimeUnit.SECONDS));
                    String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                    assertEquals(output, 0, process.exitValue());
                    assertTrue(output, output.lines().anyMatch(line -> line.equals("RUN_DIGEST=" + expected)));
                } finally {
                    process.destroyForcibly();
                }
            }
        } finally {
            Files.deleteIfExists(arguments);
        }
    }

    /**
     * 新 JVM 具有独立的不可变集合遍历种子；不连接真实数据源或外部服务。
     */
    public static final class DigestProbe {
        public static void main(String[] args) {
            try (var r = new Rig()) {
                System.out.println("RUN_DIGEST=" + ToolChatRunDigests.submission(r.input("run", "key")));
            }
        }
    }
}
