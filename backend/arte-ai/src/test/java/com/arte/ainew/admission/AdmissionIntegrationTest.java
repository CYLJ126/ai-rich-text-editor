package com.arte.ainew.admission;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.persistence.codec.JacksonExecutionRecordCodec;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.entry.EntryRequests;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationOptions;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.generation.ModelResult;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.core.enums.ResultCodeEnum;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.*;

/**
 * 无供应商的完整受理集成：真实事务、两个应用实例、授权隔离、故障回滚与持久化结果。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
public class AdmissionIntegrationTest {
    private JdbcDataSource dataSource;
    private JdbcTemplate jdbc;
    private Scheduler scheduler;
    private AdmissionFixture first;
    private AdmissionFixture second;

    @Before
    public void setup() {
        dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + UUID.randomUUID() + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        new ResourceDatabasePopulator(new ClassPathResource("arte-ai-new-ddl-mysql.sql")).execute(dataSource);
        jdbc = new JdbcTemplate(dataSource);
        scheduler = Schedulers.newBoundedElastic(8, 256, "admission-test");
        first = new AdmissionFixture(dataSource, scheduler);
        second = new AdmissionFixture(dataSource, scheduler);
    }

    @After
    public void cleanup() {
        jdbc.execute("DROP ALL OBJECTS");
        scheduler.dispose();
    }

    private Conversation conversation(String key) {
        return first.conversations.create("test", null, List.of(), first.context("alice", key)).block();
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private Invocation invocation(AcceptedExecution accepted) {
        return second.executions.find(AdmissionFixture.owner("alice-id"), accepted.executionId()).block();
    }

    private AcceptedExecution submit(Conversation conversation, String key, String text) {
        var context = first.context("alice", key);
        return first.chat.submit(first.chatRequest(conversation.conversationId(), 0, text, context), context).block();
    }

    private static void code(ResultCodeEnum expected, Runnable action) {
        assertEquals(expected, assertThrows(AdmissionException.class, action::run).getResultCode());
    }

    @Test
    public void reliableAcceptancePersistsInputTurnAndDispatchWithoutCreatingAttemptOrCallingModel() {
        first.initializeBudget("alice");
        var conversation = conversation("create");
        var accepted = submit(conversation, "submit", "hello 世界");
        var invocation = invocation(accepted);
        assertEquals(Invocation.State.ACCEPTED, invocation.state());
        assertNull(invocation.activeAttemptId());
        var snapshot = second.payloads.find(AdmissionFixture.owner("alice-id"), invocation.contextSnapshotId()).block();
        assertNotNull(snapshot);
        assertTrue(snapshot.estimatedTokens());
        assertEquals(invocation.request().input(), new GenerationRequest(snapshot.messages(), new GenerationOptions(128, null, null, List.of()),
                List.of(), new GenerationRequest.TextOutput()));
        assertNotNull(second.conversations.turn(conversation.conversationId(), invocation.conversation().turnId(), second.context("alice", "read")).block());
        assertEquals(1, second.conversations.find(conversation.conversationId(), second.context("alice", "find")).block().version());
        assertEquals(1, count("arte_ai_invocation"));
        assertEquals(1, count("arte_ai_turn"));
        assertEquals(0, count("arte_ai_attempt"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_outbox WHERE kind='DISPATCH'", Long.class).longValue());
        assertEquals(1, count("arte_ai_event"));
        assertEquals(0, second.executions.account(AdmissionFixture.owner("alice-id"), "alice-budget").block().held().amount().signum());
    }

    @Test
    public void replayIgnoresNewServerIdsDeadlinesAndAlreadyAdvancedConversationVersion() {
        first.initializeBudget("alice");
        var conversation = conversation("create");
        var accepted = submit(conversation, "same", "hello");
        var newContext = second.context("alice", "same");
        var replay = second.chat.submit(second.chatRequest(conversation.conversationId(), 0, "hello", newContext), newContext).block();
        assertEquals(accepted, replay);
        assertNotEquals(newContext.executionId(), accepted.executionId());
        assertEquals(1, count("arte_ai_invocation"));
        assertEquals(1, count("arte_ai_context_snapshot"));
        assertEquals(1, count("arte_ai_turn"));
        var changed = second.context("alice", "same");
        code(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT, () -> second.chat.submit(second.chatRequest(conversation.conversationId(), 0, "changed", changed), changed).block());
        var changedTimeoutContext = second.context("alice", "same");
        var original = second.chatRequest(conversation.conversationId(), 0, "hello", changedTimeoutContext);
        var smaller = new ExecutionOptions(changedTimeoutContext.deadline().minusSeconds(60), 1, 4096, 0, 0, Duration.ofMinutes(1));
        var changedTimeout = new EntryRequests.Chat(original.conversationId(), 0, null, null, original.context(), original.capability(),
                original.binding(), original.generationOptions(), smaller);
        code(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT, () -> second.chat.submit(changedTimeout, changedTimeoutContext).block());
    }

    @Test
    public void concurrentInstancesAcceptSameRequestOnceAndRejectCompetingNewTurn() {
        first.initializeBudget("alice");
        var conversation = conversation("create");
        var replies = Flux.range(0, 8).flatMap(index -> {
            var fixture = index % 2 == 0 ? first : second;
            var current = fixture.context("alice", "same");
            return fixture.chat.submit(fixture.chatRequest(conversation.conversationId(), 0, "hello", current), current);
        }, 8).collectList().block(Duration.ofSeconds(15));
        assertEquals(1, replies.stream().map(AcceptedExecution::executionId).distinct().count());
        assertEquals(1, count("arte_ai_invocation"));
        assertEquals(1, count("arte_ai_turn"));
        code(ResultCodeEnum.AI_VERSION_CONFLICT, () -> submit(conversation, "different-key", "another"));
        var current = first.context("alice", "busy");
        code(ResultCodeEnum.AI_CONVERSATION_BUSY, () -> first.chat.submit(first.chatRequest(conversation.conversationId(), 1, "another", current), current).block());
    }

    @Test
    public void conversationCreationIsDurablyIdempotentAcrossInstancesAndLaterVersionChanges() {
        first.initializeBudget("alice");
        var created = Flux.range(0, 8).flatMap(index -> {
            var fixture = index % 2 == 0 ? first : second;
            return fixture.conversations.create("test", null, List.of(), fixture.context("alice", "same-create"));
        }, 8).collectList().block();
        assertEquals(1, created.stream().map(Conversation::conversationId).distinct().count());
        assertEquals(1, count("arte_ai_conversation_creation"));
        assertEquals(1, count("arte_ai_conversation_new"));
        submit(created.getFirst(), "submit", "hello");
        var replay = second.conversations.create("test", null, List.of(), second.context("alice", "same-create")).block();
        assertEquals(created.getFirst().conversationId(), replay.conversationId());
        assertEquals(1, replay.version());
        code(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT, () -> second.conversations.create("changed", null, List.of(), second.context("alice", "same-create")).block());
    }

    @Test
    public void currentAuthorizationAndOwnerIsolationProtectConversationSnapshotAndBudget() {
        first.initializeBudget("alice");
        first.initializeBudget("bob");
        var conversation = conversation("create");
        var accepted = submit(conversation, "submit", "private");
        code(ResultCodeEnum.AI_CONVERSATION_NOT_FOUND, () -> second.conversations.find(conversation.conversationId(), second.context("bob", "find")).block());
        var foreign = second.context("bob", "submit");
        code(ResultCodeEnum.AI_CONVERSATION_NOT_FOUND, () -> second.chat.submit(second.chatRequest(conversation.conversationId(), 0, "hello", foreign), foreign).block());
        code(ResultCodeEnum.AI_CONTEXT_NOT_FOUND, () -> second.contexts.find(invocation(accepted).contextSnapshotId(), second.context("bob", "read")).block());
        assertThrows(AccessDeniedException.class, () -> first.context("mallory", "test"));
        var readOnly = first.context("alice", "test", Set.of("ai:read"));
        assertThrows(AccessDeniedException.class, () -> first.conversations.create("test", null, List.of(), readOnly).block());
    }

    @Test
    public void unsupportedSelectionsAndMissingBudgetCannotProduceAcceptedRecords() {
        var conversation = conversation("create");
        code(ResultCodeEnum.AI_BUDGET_NOT_INITIALIZED, () -> submit(conversation, "submit", "hello"));
        assertEquals(0, count("arte_ai_invocation"));
        assertEquals(0, count("arte_ai_context_snapshot"));
        first.initializeBudget("alice");
        var current = first.context("alice", "retry");
        var original = first.chatRequest(conversation.conversationId(), 0, "hello", current);
        var retried = new EntryRequests.Chat(original.conversationId(), 0, null, null, original.context(), original.capability(), original.binding(),
                original.generationOptions(), new ExecutionOptions(current.deadline(), 2, 4096, 1, 1, AdmissionFixture.TIMEOUT));
        code(ResultCodeEnum.AI_EXECUTION_LIMIT_EXCEEDED, () -> first.chat.submit(retried, current).block());
        var system = new ChatMessage("system", ChatMessage.Role.SYSTEM, List.of(new ChatMessage.Text("system")), List.of(), null);
        var selection = new com.arte.ainew.pojo.context.ContextRequest(List.of(system), null, List.of(), List.of(), null, original.context().budget());
        var elevated = new EntryRequests.Chat(original.conversationId(), 0, null, null, selection, original.capability(), original.binding(),
                original.generationOptions(), original.options());
        code(ResultCodeEnum.AI_ONLY_SINGLE_USER_TEXT_SUPPORTED, () -> first.chat.submit(elevated, current).block());
        code(ResultCodeEnum.AI_NOT_FOUND, () -> first.chat.regenerate(new EntryRequests.Regenerate("id", 0, original.options()), current).block());
        assertEquals(0, count("arte_ai_invocation"));
    }

    @Test
    public void failureInsideAcceptanceRollsBackTurnVersionEventsAndOutbox() {
        first.initializeBudget("alice");
        var conversation = conversation("create");
        var standard = new JacksonExecutionRecordCodec();
        var failureCodec = new ExecutionRecordCodec() {
            public String encode(Object value) {
                if (value instanceof Invocation) {
                    throw new IllegalStateException("Injected encode failure");
                }
                return standard.encode(value);
            }

            public <T> T decode(String value, Class<T> type) {
                return standard.decode(value, type);
            }
        };
        var faulty = new AdmissionFixture(dataSource, scheduler, failureCodec, AdmissionFixture.properties());
        var current = faulty.context("alice", "submit");
        assertThrows(IllegalStateException.class, () -> faulty.chat.submit(faulty.chatRequest(conversation.conversationId(), 0, "hello", current), current).block());
        assertEquals(0, count("arte_ai_invocation"));
        assertEquals(0, count("arte_ai_turn"));
        assertEquals(0, count("arte_ai_event"));
        assertEquals(0, count("arte_ai_outbox"));
        assertEquals(0, first.conversations.find(conversation.conversationId(), first.context("alice", "read")).block().version());
        assertEquals(1, count("arte_ai_context_snapshot")); // 独立字节事务留下孤立快照，但不能返回已受理。
    }

    @Test
    public void storedContextAndResultSurviveReassemblyAndRejectCorruptionOrConflictingKeys() {
        first.initializeBudget("alice");
        var accepted = submit(conversation("create"), "submit", "hello");
        var invocation = invocation(accepted);
        var owner = AdmissionFixture.owner("alice-id");
        var context = first.payloads.find(owner, invocation.contextSnapshotId()).block();
        assertEquals(StoreOutcome.Code.REPLAYED, second.payloads.put(owner, context).block().code());
        var attempt = first.executions.createAttempt(new ExecutionCommands.CreateAttempt(
                new ExecutionCommands.Version(owner, accepted.executionId(), invocation.version()), "attempt", "worker", Duration.ofMinutes(1))).block().value();
        var answer = new ChatMessage("answer", ChatMessage.Role.ASSISTANT, List.of(new ChatMessage.Text("answer")), List.of(), null);
        var value = new InvocationResult.Generation(new ModelResult("logical-result", new ModelResult.ModelIdentity("test-provider", "test-model", null),
                List.of(answer), ModelResult.FinishReason.STOP, true, null, Usage.unknown(), List.of()));
        var stored = first.payloads.put(owner, accepted.executionId(), attempt.attemptId(), "final", value).block();
        assertFalse(stored.value().partial());
        assertEquals(value, second.payloads.find(owner, accepted.executionId(), stored.value()).block());
        assertEquals(StoreOutcome.Code.REPLAYED, second.payloads.put(owner, accepted.executionId(), attempt.attemptId(), "final", value).block().code());
        var different = new InvocationResult.Generation(new ModelResult("different", value.value().model(), List.of(answer),
                ModelResult.FinishReason.STOP, true, null, Usage.unknown(), List.of()));
        assertEquals(StoreOutcome.Code.IDEMPOTENCY_CONFLICT, second.payloads.put(owner, accepted.executionId(), attempt.attemptId(), "final", different).block().code());
        var oversized = new InvocationResult.Generation(new ModelResult("oversized", value.value().model(), List.of(
                new ChatMessage("large-answer", ChatMessage.Role.ASSISTANT, List.of(new ChatMessage.Text("x".repeat(5000))), List.of(), null)),
                ModelResult.FinishReason.STOP, true, null, Usage.unknown(), List.of()));
        assertThrows(IllegalArgumentException.class, () -> second.payloads.put(owner, accepted.executionId(), attempt.attemptId(), "large", oversized).block());
        assertEquals(1, count("arte_ai_result"));
        assertNull(second.payloads.find(AdmissionFixture.owner("bob-id"), accepted.executionId(), stored.value()).block());
        var fake = new ResultRef(stored.value().resultId(), stored.value().resultType(), 1, "0".repeat(64), false);
        assertThrows(IllegalStateException.class, () -> second.payloads.find(owner, accepted.executionId(), fake).block());
        jdbc.update("UPDATE arte_ai_result SET snapshot=? WHERE id_key=?", "{}", stored.value().resultId());
        assertThrows(IllegalStateException.class, () -> second.payloads.find(owner, accepted.executionId(), stored.value()).block());
    }

    @Test
    public void budgetInitializationPreservesExistingLedgerAndRequiresSeparatePrivilege() {
        first.initializeBudget("alice");
        jdbc.update("UPDATE arte_ai_account SET snapshot=?", first.codec.encode(new com.arte.ainew.pojo.budget.BudgetCommands.Account(
                "alice-budget", AdmissionFixture.owner("alice-id"), AdmissionFixture.money("100"), AdmissionFixture.money("3"),
                AdmissionFixture.money("7"), AdmissionFixture.RATE, 2)));
        var account = second.budgets.initialize("alice-budget", second.context("alice", "initialize", Set.of("ai:budget:admin"))).block();
        assertEquals(AdmissionFixture.money("3"), account.held());
        assertEquals(AdmissionFixture.money("7"), account.charged());
        assertEquals(2, account.version());
        assertThrows(AccessDeniedException.class, () -> first.budgets.initialize("alice-budget", first.context("alice", "not-admin")).block());
        code(ResultCodeEnum.AI_BUDGET_NOT_AVAILABLE, () -> second.budgets.initialize("alice-budget", second.context("bob", "admin", Set.of("ai:budget:admin"))).block());
    }

    @Test
    public void callerConstructedSnapshotCannotForgeCounterDigestOrOutputReservation() {
        first.initializeBudget("alice");
        var current = first.context("alice", "independent");
        var input = first.chatRequest("unused", 0, "hello", current);
        var binding = first.catalog.resolve(AdmissionFixture.BINDING, AdmissionFixture.CAP, current).block();
        var snapshot = first.contexts.assemble(input.context(), binding, current).block();
        var generation = new GenerationRequest(snapshot.messages(), input.generationOptions(), List.of(), new GenerationRequest.TextOutput());
        var request = new InvocationRequest<>(AdmissionFixture.CAP, AdmissionFixture.BINDING, generation.kind(), generation, input.options(), current);
        var forged = new com.arte.ainew.pojo.context.ContextSnapshot(snapshot.snapshotId(), null, snapshot.modelBinding(), snapshot.messages(), List.of(),
                snapshot.budget(), 1, true, snapshot.tokenizerVersion(), List.of(), snapshot.contentDigest(), snapshot.createdAt(), snapshot.expiresAt());
        code(ResultCodeEnum.AI_INVALID_CONTEXT_SNAPSHOT, () -> first.coordinator.submit(new InvocationSubmission<>(request, forged, null, null, null)).block());
        var invokeOnly = first.context("alice", "invoke-only", Set.of("ai:invoke"));
        var restrictedRequest = new InvocationRequest<>(AdmissionFixture.CAP, AdmissionFixture.BINDING, generation.kind(), generation,
                first.chatRequest("unused", 0, "hello", invokeOnly).options(), invokeOnly);
        assertThrows(AccessDeniedException.class, () -> first.coordinator.submit(new InvocationSubmission<>(restrictedRequest, snapshot,
                new Invocation.ConversationLink("unused", 0, "unused-turn"), null, null)).block());
        var accepted = first.coordinator.submit(new InvocationSubmission<>(request, snapshot, null, null, null)).block();
        assertNull(invocation(accepted).conversation());
        assertEquals(0, count("arte_ai_turn"));
        var replayContext = second.context("alice", "independent");
        var fresh = second.chatRequest("unused", 0, "hello", replayContext);
        var freshSnapshot = second.contexts.assemble(fresh.context(), binding, replayContext).block();
        var freshGeneration = new GenerationRequest(freshSnapshot.messages(), fresh.generationOptions(), List.of(), new GenerationRequest.TextOutput());
        var freshRequest = new InvocationRequest<>(AdmissionFixture.CAP, AdmissionFixture.BINDING, freshGeneration.kind(), freshGeneration, fresh.options(), replayContext);
        assertEquals(accepted, second.coordinator.submit(new InvocationSubmission<>(freshRequest, freshSnapshot, null, null, null)).block());
    }

    @Test
    public void currentGrantRevocationRejectsPreviouslyAuthorizedContext() {
        first.initializeBudget("alice");
        var conversation = conversation("create");
        var current = first.context("alice", "submit");
        var original = AdmissionFixture.properties();
        var grants = original.grants().stream().map(g -> new com.arte.ainew.config.NewAiProperties.Grant(g.subjectName(), g.subjectId(), g.principalKind(),
                g.tenantId(), g.workspaceId(), g.grantRef(), !g.subjectName().equals("alice"), g.scopes(), g.bindingIds(), g.budgetRefs())).toList();
        var revoked = new com.arte.ainew.config.NewAiProperties(true, original.dataSourceBean(), original.releaseRef(), original.persistence(), original.limits(),
                grants, original.capabilities(), original.bindings(), original.connections(), original.rates(), original.budgets());
        var restarted = new AdmissionFixture(dataSource, scheduler, first.codec, revoked);
        assertThrows(AccessDeniedException.class, () -> restarted.chat.submit(first.chatRequest(conversation.conversationId(), 0, "hello", current), current).block());
        assertEquals(0, count("arte_ai_invocation"));
    }

    @Test
    public void oldSnapshotWithoutRequestedTimeoutRemainsReadableAndFixedVersionCannotBeSubstituted() {
        first.initializeBudget("alice");
        var accepted = submit(conversation("create"), "submit", "hello");
        var original = invocation(accepted);
        var json = first.codec.encode(original);
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        var root = mapper.readTree(json);
        ((tools.jackson.databind.node.ObjectNode) root.at("/body/request/options")).remove("requestedTimeout");
        var legacy = first.codec.decode(mapper.writeValueAsString(root), Invocation.class);
        assertNull(legacy.request().options().requestedTimeout());
        assertEquals(original.request().options().deadline(), legacy.request().options().deadline());
        var current = first.context("alice", "resolve");
        code(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE, () -> first.catalog.resolve(new com.arte.ainew.common.reference.DefinitionRef("binding", "text", "v2"), AdmissionFixture.CAP, current).block());
        assertEquals(AdmissionFixture.CONNECTION, first.catalog.resolveConnection(AdmissionFixture.CONNECTION, current).block().definition());
    }
}
