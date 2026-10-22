package com.arte.ainew.tool.execution;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.tool.observation.ToolObservationService;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionContextFactory;
import com.arte.ainew.persistence.mybatis.MybatisToolObservationStore;
import com.arte.ainew.pojo.budget.Reconciliation;
import com.arte.ainew.pojo.execution.Attempt;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.execution.Usage;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.auth.ExecutionReadAuthorizationResolver;
import com.arte.ainew.spi.auth.ToolInvocationAccess;
import com.arte.ainew.web.ConversationExceptionHandler;
import com.arte.ainew.web.NewAiHttpContext;
import com.arte.ainew.web.controller.NewAiToolObservationController;
import org.junit.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

/**
 * 临时 H2 的真实投影／事务／账本／HTTP；无模型、业务库或部署数据库操作。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/22 14:40 ✾
 */
public class ToolObservationIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private static final class Rig implements AutoCloseable {
        final ToolExecutionFixture f;
        final AtomicBoolean global = new AtomicBoolean(true), readable = new AtomicBoolean(true);
        final AtomicInteger reads = new AtomicInteger(), snapshotReads = new AtomicInteger(), writeResolves = new AtomicInteger();
        boolean revokeGlobalAfterRead;
        int revokeAfter = Integer.MAX_VALUE;
        final NewAiProperties properties = new NewAiProperties(false, "appDataSource", "release", null, null, List.of(), List.of(), List.of(), List.of(), List.of(), List.of());
        final ExecutionReadAuthorizationResolver resolver = new ExecutionReadAuthorizationResolver() {
            public Mono<ExecutionAuthorization> resolve(String name, String tenant, String workspace, Set<String> scopes) {
                writeResolves.incrementAndGet();
                return current(name, tenant, workspace, scopes);
            }

            public Mono<Snapshot> readSnapshot(String name, String tenant, String workspace, Set<String> scopes, boolean configuration) {
                snapshotReads.incrementAndGet();
                assertFalse(configuration);
                return current(name, tenant, workspace, scopes).map(auth -> new Snapshot(auth, null));
            }

            private Mono<ExecutionAuthorization> current(String name, String tenant, String workspace, Set<String> scopes) {
                return global.get()
                        ? Mono.just(new ExecutionAuthorization(new ExecutionPrincipal("subject", name, ExecutionPrincipal.Kind.USER), tenant, workspace, scopes, "read-grant"))
                        : Mono.error(new AccessDeniedException("Test revoked"));
            }
        };
        final AdmissionAuthorization authorization;
        final MybatisToolObservationStore store;
        final ToolObservationService service;

        Rig() throws Exception {
            this(new ToolExecutionFixture());
        }

        Rig(ToolExecutionFixture fixture) {
            f = fixture;
            authorization = new AdmissionAuthorization(resolver, properties, f.clock, null, f.io, (invocation, context, operation) -> Mono.defer(() -> {
                if (!readable.get() || reads.incrementAndGet() > revokeAfter)
                    return Mono.error(new AccessDeniedException("Test tool read revoked"));
                if (revokeGlobalAfterRead) global.set(false);
                return Mono.just(new ToolInvocationAccess.Permit() {
                    public ExecutionAuthorization authorization() {
                        return context.authorization();
                    }

                    public void requireInCurrentTransaction() {
                        throw new AssertionError("Observation must not write");
                    }
                });
            }));
            store = new MybatisToolObservationStore(f.source, f.codec, f.io);
            service = new ToolObservationService(store, authorization, f.clock);
        }

        ToolObservation.Query query() {
            return new ToolObservation.Query(Instant.ofEpochMilli(f.clock.millis() - 86_400_000), Instant.ofEpochMilli(f.clock.millis() + 60_000), null, null, null);
        }

        ExecutionContext context() {
            var c = f.context("read", "unused");
            return new ExecutionContext(c.executionId(), c.traceId(), new ExecutionAuthorization(c.authorization().principal(), "tenant", "workspace", Set.of("ai:read"), "read-grant"), c.deadline(), null, null, "release", null);
        }

        ToolObservation.Page list() {
            return service.list(query(), null, 10, context()).block(WAIT);
        }

        ToolObservation.Statistics stats() {
            return service.statistics(query(), context()).block(WAIT);
        }

        MockMvc mvc() {
            return standaloneSetup(new NewAiToolObservationController(service, new NewAiHttpContext(new ExecutionContextFactory(resolver, f.clock), properties), properties))
                    .setControllerAdvice(new ConversationExceptionHandler()).setAsyncRequestTimeout(20_000).build();
        }

        public void close() throws Exception {
            SecurityContextHolder.clearContext();
            f.close();
        }
    }

    @Test
    public void replayCountsOneLogicalInvocationAndOneAttemptWithFreeCostAndSafeSummary() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            r.f.service.submit(r.f.input(), r.f.context("replay", "one")).block(WAIT);
            r.f.worker(r.f.dispatcher).pollOnce().block(WAIT);
            var item = r.list().records().getFirst();
            assertEquals("one", item.invocationId());
            assertEquals("FREE", item.cost().state());
            assertNull(item.cost().settledAmount());
            assertEquals(1, item.attemptCount());
            assertEquals(0, item.retryCount());
            assertNull(item.outputBytes());
            assertNotNull(item.timings().admissionToAttemptMillis());
            assertNotNull(item.timings().terminalSpanMillis());
            var stats = r.stats();
            assertEquals(1, stats.invocations());
            assertEquals(1, stats.attempts());
            assertEquals("100.00", stats.successRate());
            assertEquals(1, stats.freeCalls());
            assertTrue(stats.currencies().isEmpty());
            assertEquals(0, stats.unknownCostCalls());
            var json = JsonMapper.builder().build().writeValueAsString(item);
            for (var secret : List.of("arguments", "authorization", "configuration", "grant-", "resource:read", "budgetRef"))
                assertFalse(json.contains(secret));
            assertEquals(1, r.f.invocations.get());
            assertEquals(1, r.f.count("arte_ai_invocation"));
        }
    }

    @Test
    public void pendingAndUnknownStaySeparateAndDoNotEnterSuccessRateDenominator() throws Exception {
        try (var r = new Rig()) {
            r.f.operation = call -> Mono.error(new IllegalStateException("test uncertain execution"));
            r.f.submit("unknown");
            r.f.worker(r.f.dispatcher).pollOnce().block(WAIT);
            r.f.submit("pending");
            var s = r.stats();
            assertEquals(2, s.invocations());
            assertEquals(Long.valueOf(1), s.states().get(Invocation.State.UNKNOWN));
            assertEquals(Long.valueOf(1), s.states().get(Invocation.State.ACCEPTED));
            assertEquals(0, s.determinedTerminals());
            assertNull(s.successRate());
            assertNull(s.terminalSpanP50Millis());
            assertEquals(1, r.f.invocations.get());
        }
    }

    @Test
    public void historicalAttemptsCountSeparatelyFromLogicalInvocationAndClockSkewIsNotZero() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            r.f.worker(r.f.dispatcher).pollOnce().block(WAIT);
            var i = r.f.invocation("one");
            var actual = r.f.attempt("one");
            // 合法的历史记录夹具：首 Attempt 已知失败，后一次成功；时钟偏差使首创建时刻早于受理时刻。
            var created = i.acceptedAt().minusMillis(1);
            var failed = new Attempt(actual.attemptId(), "one", 1, "old-worker", 1, created.plusSeconds(30), actual.version() + 1,
                    Attempt.State.FAILED, Attempt.Dispatch.CONFIRMED, null, null, Usage.unknown(),
                    new ExecutionError("KNOWN_FAILURE", ExecutionError.Phase.INVOCATION, true, ExecutionError.SideEffect.NONE, ExecutionError.Certainty.KNOWN, "trace-one"), created, i.acceptedAt());
            var success = new Attempt("second-attempt", "one", 2, "new-worker", 2, actual.createdAt().plusSeconds(30), actual.version(),
                    Attempt.State.SUCCEEDED, Attempt.Dispatch.CONFIRMED, null, null, actual.usage(), null, actual.createdAt(), actual.updatedAt());
            r.f.jdbc.update("UPDATE arte_ai_attempt SET workspace_snapshot=? WHERE id_key=?", r.f.codec.encode(failed), CanonicalJson.key(actual.attemptId()));
            r.f.jdbc.update("INSERT INTO arte_ai_attempt(id_key,invocation_key,attempt_number,purpose,workspace_snapshot) VALUES(?,?,?,?,?)",
                    CanonicalJson.key(success.attemptId()), CanonicalJson.key("one"), 2, "EXECUTE", r.f.codec.encode(success));
            var updated = new Invocation(i.request(), i.requestDigest(), i.conversation(), i.contextSnapshotId(), i.replacesInvocationId(), i.state(),
                    i.version() + 1, success.attemptId(), i.result(), i.error(), i.acceptedAt(), i.updatedAt());
            r.f.jdbc.update("UPDATE arte_ai_invocation SET workspace_snapshot=?,next_attempt=2,next_fence=2 WHERE id_key=?", r.f.codec.encode(updated), CanonicalJson.key("one"));
            var s = r.stats();
            assertEquals(1, s.invocations());
            assertEquals(2, s.attempts());
            assertEquals(1, s.retries());
            assertNull(r.list().records().getFirst().timings().admissionToAttemptMillis());
        }
    }

    @Test
    public void paidSettledAmountAndKnownZeroRemainDifferentFromFree() throws Exception {
        for (var price : List.of("0.5", "0"))
            try (var r = new Rig(new ToolExecutionFixture(ToolBillingIntegrationTest.price(price)))) {
                r.f.operation = c -> Mono.just(ToolBillingIntegrationTest.success(c, new ToolCharge(ToolBillingIntegrationTest.money(price), "invoice")));
                r.f.submit("paid");
                r.f.worker(r.f.dispatcher).pollOnce().block(WAIT);
                var s = r.stats();
                assertEquals(0, s.freeCalls());
                assertEquals(1, s.chargedCalls());
                assertEquals(0, s.unknownCostCalls());
                assertEquals(price.equals("0") ? 1 : 0, s.knownZeroCostCalls());
                assertEquals(price, s.currencies().getFirst().settledAmount());
                assertEquals("SETTLED", r.list().records().getFirst().cost().state());
                assertEquals(1, r.f.count("arte_ai_reservation"));
                assertEquals(1, r.f.invocations.get());
            }
    }

    @Test
    public void pendingChargeIsNullAndHeldAmountNeverMasqueradesAsActualCost() throws Exception {
        try (var r = new Rig(new ToolExecutionFixture(ToolBillingIntegrationTest.price("0.5")))) {
            r.f.operation = call -> Mono.error(new IllegalStateException("test unknown bill"));
            r.f.submit("paid");
            r.f.worker(r.f.dispatcher).pollOnce().block(WAIT);
            var cost = r.list().records().getFirst().cost();
            assertTrue(cost.unknown());
            assertNull(cost.settledAmount());
            assertEquals("0.5", cost.reservedAmount());
            assertEquals("PENDING_RECONCILIATION", cost.state());
            assertEquals(1, r.stats().unknownCostCalls());
            assertEquals("0", r.stats().currencies().getFirst().settledAmount());
        }
    }

    @Test
    public void ledgerReconciliationCanDetermineFeeWhileBusinessRemainsUnknown() throws Exception {
        try (var r = new Rig(new ToolExecutionFixture(ToolBillingIntegrationTest.price("0.5")))) {
            r.f.operation = call -> Mono.just(new ToolResult(call.call().request().input().callId(), ToolResult.Outcome.UNKNOWN, null, List.of(), List.of(),
                    ExecutionError.SideEffect.POSSIBLE, new ExecutionError("TOOL_UNKNOWN", ExecutionError.Phase.INVOCATION, false,
                    ExecutionError.SideEffect.POSSIBLE, ExecutionError.Certainty.UNKNOWN, "trace")));
            r.f.submit("paid");
            r.f.worker(r.f.dispatcher).pollOnce().block(WAIT);
            var i = r.f.invocation("paid");
            var reserve = ToolBillingIntegrationTest.reservation(r.f, "paid");
            assertEquals(Invocation.State.UNKNOWN, i.state());
            assertTrue(r.f.executions.confirmReconciliation(new Reconciliation.Confirm(r.f.owner, i.request().context().authorization().principal(), "trace", "manual", "tool-wallet", "paid", i.version(),
                    reserve.reservationId(), reserve.version(), ToolBillingIntegrationTest.money("0.4"), "invoice", "test ledger proof", true)).block(WAIT).successful());
            var s = r.stats();
            assertEquals(Long.valueOf(1), s.states().get(Invocation.State.UNKNOWN));
            assertNull(s.successRate());
            assertEquals(0, s.unknownCostCalls());
            assertEquals("0.4", s.currencies().getFirst().settledAmount());
            assertEquals(1, r.f.invocations.get());
        }
    }

    @Test
    public void cursorPagesDoNotDuplicateOrSkipAndCannotCrossOwnerWindowOrFilters() throws Exception {
        try (var r = new Rig()) {
            for (int n = 0; n < 7; n++) r.f.submit("invocation-" + n);
            // 同毫秒受理的读取夹具；分页必须使用 Invocation 主键作为第二排序键。
            var tiedAt = Instant.ofEpochMilli(r.f.invocation("invocation-6").acceptedAt().toEpochMilli() + 1);
            for (int n = 0; n < 7; n++) {
                var i = r.f.invocation("invocation-" + n);
                var tied = new Invocation(i.request(), i.requestDigest(), i.conversation(), i.contextSnapshotId(), i.replacesInvocationId(), i.state(),
                        i.version(), i.activeAttemptId(), i.result(), i.error(), tiedAt, tiedAt);
                r.f.jdbc.update("UPDATE arte_ai_invocation SET workspace_snapshot=? WHERE id_key=?", r.f.codec.encode(tied), CanonicalJson.key("invocation-" + n));
                r.f.jdbc.update("UPDATE arte_ai_tool_execution_snapshot SET accepted_at=? WHERE invocation_key=?", tiedAt.toEpochMilli(), CanonicalJson.key("invocation-" + n));
            }
            var query = r.query();
            var ids = new HashSet<String>();
            String cursor = null;
            do {
                var page = r.service.list(query, cursor, 2, r.context()).block(WAIT);
                for (var item : page.records()) assertTrue(ids.add(item.invocationId()));
                cursor = page.nextCursor();
            } while (cursor != null);
            assertEquals(7, ids.size());
            var next = r.service.list(query, null, 2, r.context()).block(WAIT).nextCursor();
            assertThrows(IllegalArgumentException.class, () -> r.service.list(new ToolObservation.Query(query.from(), query.until(), null, "other", null), next, 2, r.context()).block(WAIT));
            var changedWindow = new ToolObservation.Query(query.from().minusMillis(1), query.until(), null, null, null);
            assertThrows(IllegalArgumentException.class, () -> r.service.list(changedWindow, next, 2, r.context()).block(WAIT));
            assertEquals(0, r.f.invocations.get());
        }
    }

    @Test
    public void exactWindowBoundariesAndFiltersApplyToAcceptedTime() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            var at = Instant.ofEpochMilli(r.f.invocation("one").acceptedAt().toEpochMilli());
            var excluded = new ToolObservation.Query(at.minusMillis(1), at, null, null, null);
            assertTrue(r.service.list(excluded, null, 10, r.context()).block(WAIT).records().isEmpty());
            var included = new ToolObservation.Query(at, at.plusMillis(1), r.f.binding.bindingId(), r.f.v1.revision().tool().definition().id(), Invocation.State.ACCEPTED);
            assertEquals(1, r.service.list(included, null, 10, r.context()).block(WAIT).records().size());
            assertTrue(r.service.list(new ToolObservation.Query(at, at.plusMillis(1), null, null, Invocation.State.SUCCEEDED), null, 10, r.context()).block(WAIT).records().isEmpty());
        }
    }

    @Test
    public void revokedToolAndFinalRecheckFilterBothRecordsAndStatisticsWithoutLeakingCounts() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            r.readable.set(false);
            assertTrue(r.list().records().isEmpty());
            assertEquals(0, r.stats().invocations());
            r.readable.set(true);
            r.reads.set(0);
            r.revokeAfter = 1;
            assertTrue(r.list().records().isEmpty());
            r.global.set(false);
            assertThrows(AccessDeniedException.class, r::stats);
        }
    }

    @Test
    public void otherWorkspaceHasNeitherRecordsNorAggregatedFacts() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            var c = r.context();
            var other = new ExecutionContext(c.executionId(), c.traceId(), new ExecutionAuthorization(c.authorization().principal(), "tenant", "other", Set.of("ai:read"), "grant"), c.deadline(), null, null, "release", null);
            assertTrue(r.service.list(r.query(), null, 10, other).block(WAIT).records().isEmpty());
            assertEquals(0, r.service.statistics(r.query(), other).block(WAIT).invocations());
        }
    }

    @Test
    public void statisticsAreExplicitlyTruncatedAndListCanContinueAfterFilteredEmptyPage() throws Exception {
        try (var r = new Rig()) {
            for (int n = 0; n < 101; n++) r.f.submit("sample-" + n);
            var s = r.stats();
            assertTrue(s.truncated());
            assertEquals(100, s.scanned());
            assertEquals(100, s.invocations());
            var q = r.query();
            var emptyFilter = new ToolObservation.Query(q.from(), q.until(), null, "not-present", null);
            var page = r.service.list(emptyFilter, null, 10, r.context()).block(WAIT);
            assertTrue(page.records().isEmpty());
            assertFalse(page.exhausted());
            assertNotNull(page.nextCursor());
            assertEquals(50, page.scanned());
            assertEquals(0, r.f.count("arte_ai_attempt"));
        }
    }

    @Test
    public void fiftyAndHundredCandidateQueriesUseTwoFreshReadBatches() throws Exception {
        try (var r = new Rig()) {
            for (int n = 0; n < 100; n++) r.f.submit("batch-" + n);
            var sizes = new ArrayList<Integer>();
            var batchAccess = new ToolInvocationAccess() {
                public Mono<Permit> authorize(Invocation invocation, ExecutionContext context, Operation operation) {
                    return Mono.error(new AssertionError("Observation must use bounded read batches"));
                }

                public Mono<List<ReadPermit>> authorizeReadBatch(List<Invocation> invocations, ExecutionContext context) {
                    sizes.add(invocations.size());
                    return Mono.just(invocations.stream().map(i -> new ReadPermit(i.request().context().executionId(), context.authorization())).toList());
                }
            };
            var authorization = new AdmissionAuthorization(r.resolver, r.properties, r.f.clock, null, r.f.io, batchAccess);
            var service = new ToolObservationService(r.store, authorization, r.f.clock);
            assertEquals(10, service.list(r.query(), null, 10, r.context()).block(WAIT).records().size());
            assertEquals(100, service.statistics(r.query(), r.context()).block(WAIT).invocations());
            assertEquals(List.of(50, 50, 100, 100), sizes);
            assertEquals(0, r.f.count("arte_ai_attempt"));
        }
    }

    @Test
    public void batchPermitsCannotSelectForeignIdsRepeatIdsOrExpandAuthorization() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            var input = List.of(r.f.invocation("one"));
            var context = r.context();
            var authorization = context.authorization();
            var expanded = new ExecutionAuthorization(authorization.principal(), authorization.tenantId(), authorization.workspaceId(),
                    Set.of(AdmissionAuthorization.READ, AdmissionAuthorization.INVOKE), authorization.grantRef());
            var foreign = new ExecutionAuthorization(authorization.principal(), authorization.tenantId(), "foreign", authorization.scopes(), authorization.grantRef());
            for (var permits : List.of(
                    List.of(new ToolInvocationAccess.ReadPermit("other", authorization)),
                    List.of(new ToolInvocationAccess.ReadPermit("one", authorization), new ToolInvocationAccess.ReadPermit("one", authorization)),
                    List.of(new ToolInvocationAccess.ReadPermit("one", expanded)),
                    List.of(new ToolInvocationAccess.ReadPermit("one", foreign)))) {
                var host = new ToolInvocationAccess() {
                    public Mono<Permit> authorize(Invocation invocation, ExecutionContext current, Operation operation) {
                        return Mono.error(new AssertionError("Unexpected single invocation authorization"));
                    }

                    public Mono<List<ReadPermit>> authorizeReadBatch(List<Invocation> invocations, ExecutionContext current) {
                        return Mono.just(permits);
                    }
                };
                var access = new AdmissionAuthorization(r.resolver, r.properties, r.f.clock, null, r.f.io, host);
                assertThrows(RuntimeException.class, () -> access.readInvocations(input, context).block(WAIT));
            }
        }
    }

    @Test
    public void unbackfilledHistoryAndCorruptMetadataAreNeverPresentedAsCompleteOrZero() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            r.f.jdbc.update("UPDATE arte_ai_tool_execution_snapshot SET accepted_at=0");
            assertTrue(r.stats().historicalIncomplete());
            assertTrue(r.list().historicalIncomplete());
            r.f.jdbc.update("UPDATE arte_ai_tool_execution_snapshot SET accepted_at=?,observation_digest=?", r.f.invocation("one").acceptedAt().toEpochMilli(), "0".repeat(64));
            assertThrows(IllegalStateException.class, r::stats);
            r.f.jdbc.update("UPDATE arte_ai_tool_execution_snapshot SET observation=?,observation_digest=?", "{}", CanonicalJson.sha256("{}"));
            assertThrows(IllegalStateException.class, r::stats);
        }
    }

    @Test
    public void projectionFailureRollsBackInvocationEventsAndDispatchTogether() throws Exception {
        try (var r = new Rig()) {
            r.f.jdbc.execute("ALTER TABLE arte_ai_tool_execution_snapshot ADD CONSTRAINT reject_observation CHECK(accepted_at=0)");
            assertThrows(RuntimeException.class, () -> r.f.submit("rollback"));
            for (var table : List.of("arte_ai_invocation", "arte_ai_tool_execution_snapshot", "arte_ai_event", "arte_ai_outbox", "arte_ai_attempt"))
                assertEquals(0, r.f.count(table));
        }
    }

    @Test
    public void projectionUsesAuthoritativeAcceptanceAndBackfillPathsMatchActualCodecShape() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            var row = r.f.jdbc.queryForMap("SELECT accepted_at,observation FROM arte_ai_tool_execution_snapshot");
            assertEquals(r.f.invocation("one").acceptedAt().toEpochMilli(), ((Number) row.get("accepted_at")).longValue());
            var metadata = ToolObservationMetadata.decode((String) row.get("observation"));
            var tree = JsonMapper.builder().build().readTree(r.f.codec.encode(r.f.store.snapshot(r.f.owner, "one").block(WAIT))).path("body");
            assertEquals(metadata.invocationId(), tree.path("request").path("context").path("executionId").asString());
            assertEquals(metadata.toolId(), tree.path("request").path("input").path("tool").path("id").asString());
            assertEquals(metadata.title(), tree.path("binding").path("tool").path("tool").path("title").asString());
            assertEquals(metadata.providerId(), tree.path("binding").path("tool").path("tool").path("providerId").asString());
            var acceptedJson = JsonMapper.builder().build().readTree(r.f.codec.encode(r.f.invocation("one"))).path("body").path("acceptedAt");
            assertTrue(acceptedJson.isString());
            assertEquals(r.f.invocation("one").acceptedAt().toEpochMilli(), Instant.parse(acceptedJson.asString()).toEpochMilli());
            var migration = new ClassPathResource("arte-ai-tool-observation-migration.sql").getContentAsString(StandardCharsets.UTF_8);
            assertTrue(migration.contains("'$.body.acceptedAt'"));
            assertTrue(migration.contains("i.id_key=t.invocation_key"));
            assertFalse(migration.contains("'$.body.receivedAt'"));
        }
    }

    @Test
    public void incrementalStructurePreservesOldSnapshotsAndMarksThemAsUnbackfilled() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            var before = r.f.jdbc.queryForObject("SELECT snapshot FROM arte_ai_tool_execution_snapshot", String.class);
            for (var index : List.of("idx_tool_observation_time", "idx_tool_observation_binding"))
                r.f.jdbc.execute("DROP INDEX " + index);
            for (var column : List.of("accepted_at", "observation", "observation_digest"))
                r.f.jdbc.execute("ALTER TABLE arte_ai_tool_execution_snapshot DROP COLUMN " + column);
            var sql = new ClassPathResource("arte-ai-tool-observation-migration.sql").getContentAsString(StandardCharsets.UTF_8);
            // H2 不支持 MySQL 单次 ALTER 的多个 ADD COLUMN；只在结构兼容测试中拆成独立 ALTER。
            // MySQL 原始 DDL／回填语法仍需要部署环境单独验收。
            var structure = sql.substring(0, sql.indexOf("-- 回填段"))
                    .replace(",\n    ADD COLUMN", ";\nALTER TABLE arte_ai_tool_execution_snapshot ADD COLUMN");
            new ResourceDatabasePopulator(new ByteArrayResource(structure.getBytes(StandardCharsets.UTF_8))).execute(r.f.source);
            assertEquals(before, r.f.jdbc.queryForObject("SELECT snapshot FROM arte_ai_tool_execution_snapshot", String.class));
            assertTrue(r.stats().historicalIncomplete());
            assertEquals(0, r.stats().invocations());
            var baseline = new ClassPathResource("arte-ai-new-ddl-mysql.sql").getContentAsString(StandardCharsets.UTF_8);
            var admission = new ClassPathResource("arte-ai-tool-admission-migration.sql").getContentAsString(StandardCharsets.UTF_8);
            var normalizedBaseline = baseline.replaceAll("\\s+", " ");
            for (var line : admission.lines().filter(s -> s.contains("accepted_at") || s.contains("observation")).toList())
                assertTrue(normalizedBaseline.contains(line.trim().replaceAll("\\s+", " ")));
        }
    }

    @Test
    public void httpRejectsIdentityInjectionAndReturnsOnlyWhitelistedSummary() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
            var q = r.query();
            var body = new HashMap<String, Object>();
            body.put("scope", Map.of("tenantId", "tenant", "workspaceId", "workspace"));
            body.put("from", q.from().toString());
            body.put("until", q.until().toString());
            var mvc = r.mvc();
            var response = performPost(mvc, "listExecutions", body);
            assertEquals(200, response.getResponse().getStatus());
            var json = response.getResponse().getContentAsString();
            assertTrue(json.contains("one"));
            assertFalse(json.contains("authorization"));
            assertFalse(json.contains("arguments"));
            assertFalse(json.contains("configuration"));
            var statistics = performPost(mvc, "getStatistics", body);
            assertEquals(200, statistics.getResponse().getStatus());
            assertTrue(statistics.getResponse().getContentAsString().contains("\"invocations\":1"));
            assertEquals(4, r.snapshotReads.get()); // 每个 HTTP 查询：入口快照及返回前当前资格。
            assertEquals(0, r.writeResolves.get());
            body.put("subjectId", "victim");
            assertEquals(400, performPost(mvc, "getStatistics", body).getResponse().getStatus());
        }
    }

    @Test
    public void emptyHttpQueriesStillRecheckCurrentReadEligibility() throws Exception {
        try (var r = new Rig()) {
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
            var q = r.query();
            var body = Map.of("scope", Map.of("tenantId", "tenant", "workspaceId", "workspace"), "from", q.from().toString(), "until", q.until().toString());
            var mvc = r.mvc();
            assertTrue(performPost(mvc, "listExecutions", body).getResponse().getContentAsString().contains("\"records\":[]"));
            assertTrue(performPost(mvc, "getStatistics", body).getResponse().getContentAsString().contains("\"invocations\":0"));
            assertEquals(4, r.snapshotReads.get());
            assertEquals(0, r.writeResolves.get());
        }
    }

    @Test
    public void globalRevocationAfterLoadingCannotReuseInitialReadSnapshot() throws Exception {
        try (var r = new Rig()) {
            r.f.submit("one");
            r.revokeGlobalAfterRead = true;
            SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of()));
            var contexts = new NewAiHttpContext(new ExecutionContextFactory(r.resolver, r.f.clock), r.properties);
            var scope = new com.arte.ainew.web.request.ConversationRequests.Scope("tenant", "workspace");
            assertThrows(AccessDeniedException.class, () -> contexts.read(scope, Set.of(AdmissionAuthorization.READ), Duration.ofSeconds(10), false,
                    context -> r.service.list(r.query(), null, 10, context)).block(WAIT));
            r.global.set(true);
            assertThrows(AccessDeniedException.class, () -> contexts.read(scope, Set.of(AdmissionAuthorization.READ), Duration.ofSeconds(10), false,
                    context -> r.service.statistics(r.query(), context)).block(WAIT));
            assertEquals(4, r.snapshotReads.get());
            assertEquals(0, r.writeResolves.get());
        }
    }

    @Test
    public void completedChatExposesToolChildWithoutCountingModelInvocations() throws Exception {
        try (var chat = new ToolChatLoopTest.Rig()) {
            chat.submit();
            chat.until(run -> run.state().terminal());
            assertEquals(ToolChatRun.State.SUCCEEDED, chat.run().state());
            var r = new Rig(chat.f); // 生命周期由聊天夹具统一关闭。
            var item = r.list().records().getFirst();
            assertEquals("CHAT_CHILD", item.source());
            assertEquals("run", item.parentExecutionId());
            assertEquals("FREE", item.cost().state());
            assertEquals(Invocation.State.SUCCEEDED, item.state());
            assertEquals(1, r.stats().invocations());
            assertEquals(1, r.stats().attempts());
            assertEquals(3, chat.count("arte_ai_invocation"));
            assertEquals(2, chat.modelCalls.get());
            assertEquals(1, chat.f.invocations.get());
        }
    }

    @Test
    public void invalidWindowsAndLimitsFailBeforeQueryingOrExecuting() throws Exception {
        try (var r = new Rig()) {
            var q = r.query();
            assertThrows(IllegalArgumentException.class, () -> new ToolObservation.Query(q.from(), q.from(), null, null, null));
            assertThrows(IllegalArgumentException.class, () -> new ToolObservation.Query(q.from(), q.from().plus(Duration.ofDays(32)), null, null, null));
            assertThrows(IllegalArgumentException.class, () -> r.service.list(q, null, 21, r.context()));
            assertThrows(IllegalArgumentException.class, () -> r.service.list(q, "bad", 10, r.context()).block(WAIT));
            assertEquals(0, r.f.count("arte_ai_invocation"));
        }
    }

    private static org.springframework.test.web.servlet.MvcResult performPost(MockMvc mvc, String path, Object body) throws Exception {
        var result = mvc.perform(post("/ai-new/tool/" + path).contentType(MediaType.APPLICATION_JSON).content(JsonMapper.builder().build().writeValueAsString(body))).andReturn();
        return result.getRequest().isAsyncStarted() ? mvc.perform(asyncDispatch(result)).andReturn() : result;
    }
}
