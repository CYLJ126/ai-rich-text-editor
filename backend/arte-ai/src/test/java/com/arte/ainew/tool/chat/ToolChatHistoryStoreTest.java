package com.arte.ainew.tool.chat;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.auth.FixedExecutionAuthorizationResolver;
import com.arte.ainew.application.context.ChatHistoryLoader;
import com.arte.ainew.application.conversation.DefaultConversationService;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.tool.chat.ToolChatQueryService;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.persistence.mybatis.MybatisConversationSearchReads;
import com.arte.ainew.persistence.mybatis.MybatisConversationSearchSource;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.conversation.AnswerCandidate;
import com.arte.ainew.pojo.conversation.Conversation;
import com.arte.ainew.pojo.conversation.ConversationList;
import com.arte.ainew.pojo.conversation.Turn;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.pojo.generation.ModelResult;
import com.arte.ainew.pojo.generation.ToolCall;
import com.arte.ainew.pojo.search.SearchSync;
import com.arte.ainew.pojo.tool.ToolChatCommands;
import com.arte.ainew.pojo.tool.ToolChatRun;
import com.arte.ainew.pojo.tool.ToolChatRunSnapshot;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.conversation.ConversationArchiveReader;
import com.arte.ainew.spi.search.ConversationSearchIndex;
import org.junit.Test;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.arte.ainew.pojo.execution.StoreOutcome.Code.*;
import static com.arte.ainew.pojo.tool.ToolChatRun.Phase.FINISHED;
import static com.arte.ainew.serialization.CanonicalJson.key;
import static org.junit.Assert.*;

/**
 * 同库事务和权威结果测试；模型子结果为固定测试夹具，无真实模型／工具／业务库。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 18:20 ✾
 */
public class ToolChatHistoryStoreTest {

    private static final Duration WAIT = Duration.ofSeconds(15);

    static final class Chat implements AutoCloseable {
        final ToolChatRunStoreTest.Rig r = new ToolChatRunStoreTest.Rig();
        final MybatisExecutionPersistence executions = new MybatisExecutionPersistence(r.source, r.codec, r.io);
        final MybatisPayloadPersistence payloads = new MybatisPayloadPersistence(r.source, r.codec, r.io);
        final AtomicBoolean archived = new AtomicBoolean();
        final ConversationArchiveReader archives = (o, ids) -> Mono.just(archived.get() ? ids : Set.of());
        final MybatisConversationSearchSource search = new MybatisConversationSearchSource(r.source, r.codec, archives);
        final MybatisConversationSearchReads reads = new MybatisConversationSearchReads(r.source, r.codec, archives);
        final ExecutionContext context;
        final DefaultConversationService conversations;
        final ChatHistoryLoader history;
        final ToolChatQueryService queries;
        final ToolChatRunSnapshot input = r.input("run", "submit");
        final Turn turn;

        Chat() {
            var scopes = Set.of(AdmissionAuthorization.READ, AdmissionAuthorization.CONVERSATION, AdmissionAuthorization.INVOKE);
            var auth = new ExecutionAuthorization(new ExecutionPrincipal("subject", "user", ExecutionPrincipal.Kind.USER), "tenant", "workspace", scopes, "grant");
            context = new ExecutionContext("query", "query-trace", auth, Instant.now().plusSeconds(120), null, null, "release", "query-key");
            var grant = new NewAiProperties.Grant("user", "subject", ExecutionPrincipal.Kind.USER, "tenant", "workspace", "grant", true, scopes, Set.of(), Set.of());
            var properties = new NewAiProperties(true, "test", "release", null, null, List.of(grant), List.of(), List.of(), List.of(), List.of(), List.of(), "DATABASE");
            var authorization = new AdmissionAuthorization(new FixedExecutionAuthorizationResolver(properties), properties, Clock.systemUTC());
            conversations = new DefaultConversationService(executions, authorization, Clock.systemUTC(), archives);
            history = new ChatHistoryLoader(conversations, authorization, executions, payloads, payloads, r.store);
            queries = new ToolChatQueryService(r.store, conversations, authorization, executions, payloads);
            var at = input.receivedAt();
            executions.createConversation(new Conversation("conversation", r.owner, "test", 0, null, List.of(), Conversation.State.ACTIVE, at, at)).block(WAIT);
            turn = Turn.typed("turn", "conversation", 1, null, null, input.initialContext().messages().getLast(), List.of(AnswerCandidate.run("run")), null, 0, at, at);
        }

        StoreOutcome<ToolChatRun> accept() {
            return r.store.acceptChat(input, turn, 0, ignored -> r.authorize()).block(WAIT);
        }

        Turn turn() {
            return executions.findTurn(r.owner, "conversation", "turn").block(WAIT);
        }

        long version() {
            return executions.findConversation(r.owner, "conversation").block(WAIT).version();
        }

        Object gate() {
            return r.jdbc.queryForObject("SELECT active_invocation FROM arte_ai_conversation_new", String.class);
        }

        ToolChatCommands.Advance finish(ToolChatRun.State state, ResultRef result) {
            r.wake();
            return new ToolChatCommands.Advance(r.claim(), 1, "finish", state, FINISHED, 0, List.of(), null, result, null);
        }

        Invocation finalModel(ModelResult.FinishReason reason) {
            var receipt = r.advance(r.model(input, r.claim(), "model")).value();
            String id = receipt.steps().getFirst().childExecutionId();
            var c = input.context();
            var childContext = new ExecutionContext(id, c.traceId(), c.authorization(), c.deadline(), c.executionId(), null, c.releaseRef(), "child-key");
            var proposals = reason == ModelResult.FinishReason.TOOL_CALLS ? List.of(new ToolCall("proposal", r.fixture.revision().tool().definition(),
                    com.arte.ainew.tool.discovery.LocalToolFixtures.object())) : List.<ToolCall>of();
            var model = new ModelResult("model-result", new ModelResult.ModelIdentity("provider", "model", null),
                    List.of(new ChatMessage("answer", ChatMessage.Role.ASSISTANT, List.of(new ChatMessage.Text("final answer")), proposals, null)),
                    reason, true, null, Usage.unknown(), List.of());
            var value = new InvocationResult.Generation(model);
            String bytes = r.codec.encode(value), digest = CanonicalJson.sha256(bytes);
            var ref = new ResultRef(key("answer-result", id), "model-result", 1, digest, false);
            var request = new InvocationRequest<>(input.model().capability().definition(), input.model().definition(), CapabilityDescriptor.Kind.GENERATION,
                    new GenerationRequest(input.initialContext().messages(), input.generation(), List.of(), new GenerationRequest.TextOutput()),
                    new ExecutionOptions(c.deadline(), 1, 4096, 0, 0), childContext);
            var invocation = new Invocation(request, "0".repeat(64), null, input.initialContext().snapshotId(), null,
                    Invocation.State.SUCCEEDED, 3, "attempt-" + id, ref, null, input.receivedAt(), Instant.now());
            String owner = key("tenant", "workspace", "subject");
            r.jdbc.update("INSERT INTO arte_ai_invocation(id_key,owner_key,workspace_snapshot,next_sequence,next_attempt,next_fence,retained_after,written_bytes) VALUES(?,?,?,0,1,1,0,0)", key(id), owner, r.codec.encode(invocation));
            r.jdbc.update("INSERT INTO arte_ai_result(id_key,owner_key,invocation_key,attempt_key,result_key,result_type,schema_version,partial,payload_digest,workspace_snapshot) VALUES(?,?,?,?,?,'model-result',1,0,?,?)",
                    ref.resultId(), owner, key(id), key(invocation.activeAttemptId()), key("result-operation", id), digest, bytes);
            return invocation;
        }

        ChatHistoryLoader.Loaded load() {
            return history.load(new ContextRequest.HistorySelection("conversation", version(), List.of(), 10), "generation", context).block(WAIT);
        }

        public void close() {
            r.close();
        }
    }

    @Test
    public void acceptanceAtomicallyAssociatesTypedTurnGateEventsAndOriginalReplay() {
        try (var c = new Chat()) {
            assertEquals(APPLIED, c.accept().code());
            assertEquals(1, c.version());
            assertEquals(key("run"), c.gate());
            assertTrue(c.turn().invocationIds().isEmpty());
            assertEquals(List.of(AnswerCandidate.run("run")), c.turn().candidates());
            assertEquals(REPLAYED, c.accept().code());
            assertEquals(1, c.r.count("arte_ai_tool_chat_run_event"));
            assertEquals(1, c.r.count("arte_ai_outbox"));
            assertEquals(1, c.r.count("arte_ai_conversation_message_fact"));
            var other = Turn.typed("turn2", "conversation2", 1, null, null, c.turn.userMessage(), c.turn.candidates(), null, 0, c.turn.createdAt(), c.turn.updatedAt());
            assertEquals(IDEMPOTENCY_CONFLICT, c.r.store.acceptChat(c.input, other, 0, s -> c.r.authorize()).block(WAIT).code());
            c.r.allowed.set(false);
            assertThrows(org.springframework.security.access.AccessDeniedException.class, c::accept);
        }
    }

    @Test
    public void outboxFailureRollsBackRootTurnGateMetadataAndEventsTogether() {
        try (var c = new Chat()) {
            c.r.jdbc.execute("ALTER TABLE arte_ai_outbox ADD CONSTRAINT injected_failure CHECK (kind <> 'RUN_EVENT')");
            assertThrows(RuntimeException.class, c::accept);
            assertEquals(0, c.r.count("arte_ai_tool_chat_run"));
            assertEquals(0, c.r.count("arte_ai_turn"));
            assertEquals(0, c.version());
            assertNull(c.gate());
            assertEquals(0, c.r.count("arte_ai_tool_chat_run_event"));
            assertEquals(0, c.r.count("arte_ai_conversation_message_fact"));
            assertEquals(0, c.r.count("arte_ai_tool_chat_link"));
        }
    }

    @Test
    public void activeRunBlocksAnotherRootAndOrdinaryInvocationAcceptance() {
        try (var c = new Chat()) {
            c.accept();
            var next = c.r.input("run2", "submit2");
            var turn = Turn.typed("turn2", "conversation", 2, null, null, next.initialContext().messages().getLast(), List.of(AnswerCandidate.run("run2")), null, 0, next.receivedAt(), next.receivedAt());
            assertEquals(CONVERSATION_BUSY, c.r.store.acceptChat(next, turn, 1, s -> c.r.authorize()).block(WAIT).code());
            var request = new InvocationRequest<>(next.model().capability().definition(), next.model().definition(), CapabilityDescriptor.Kind.GENERATION,
                    new GenerationRequest(next.initialContext().messages(), next.generation(), List.of(), new GenerationRequest.TextOutput()),
                    new ExecutionOptions(next.context().deadline(), 1, 4096, 0, 0), next.context());
            var invocation = new Invocation(request, "0".repeat(64), new Invocation.ConversationLink("conversation", 1, "plain-turn"), next.initialContext().snapshotId(), null,
                    Invocation.State.ACCEPTED, 0, null, null, null, next.receivedAt(), next.receivedAt());
            var plain = new Turn("plain-turn", "conversation", 2, null, null, turn.userMessage(), List.of("run2"), null, 0, next.receivedAt(), next.receivedAt());
            assertEquals(CONVERSATION_BUSY, c.executions.accept(new ExecutionCommands.Accept(invocation, plain)).block(WAIT).code());
            assertEquals(1, c.r.count("arte_ai_turn"));
            assertThrows(AdmissionException.class, c::load);
        }
    }

    @Test
    public void onlyVerifiedFinalModelAnswerEntersHistorySearchAndQueryAndArchiveApplies() {
        try (var c = new Chat()) {
            c.accept();
            var child = c.finalModel(ModelResult.FinishReason.STOP);
            var unit = new SearchSync.Unit(SearchSync.Kind.I, key(child.request().context().executionId()));
            assertTrue(c.search.read(unit, null).records().isEmpty());
            assertTrue(c.reads.readableInvocations(c.r.owner, Map.of(child.request().context().executionId(), child)).isEmpty());
            var finish = c.finish(ToolChatRun.State.SUCCEEDED, child.result());
            assertEquals(APPLIED, c.r.advance(finish).code());
            assertNull(c.gate());
            assertEquals(2, c.version());
            assertEquals(AnswerCandidate.run("run"), c.turn().selectedCandidate());
            assertNull(c.turn().selectedInvocationId());
            var loaded = c.load();
            assertEquals(2, loaded.messages().size());
            assertEquals("final answer", ((ChatMessage.Text) loaded.messages().getLast().content().getFirst()).text());
            var source = c.search.read(unit, null);
            assertEquals(1, source.records().size());
            var record = source.records().getFirst();
            assertEquals(child.request().context().executionId(), record.invocationId());
            assertEquals(Integer.valueOf(1), record.candidateOrdinal());
            var hit = new ConversationSearchIndex.Hit(record.entryKey(), record.source(), List.of(1, 1, 1, 1), Map.of());
            var visible = c.reads.visible(c.r.owner, List.of(hit));
            assertEquals(AnswerCandidate.run("run"), visible.get(record.entryKey()).candidate());
            assertEquals(1, c.reads.readableInvocations(c.r.owner, Map.of(record.invocationId(), child)).size());
            assertEquals("run", c.queries.result("run", c.context).block(WAIT).runId());
            assertEquals(REPLAYED, c.r.advance(finish).code());
            assertEquals(3, c.r.count("arte_ai_tool_chat_run_event"));
            assertEquals(2, c.r.count("arte_ai_conversation_message_fact"));
            c.archived.set(true);
            assertEquals(SearchSync.Disposition.ARCHIVED, c.search.read(unit, source.scope()).disposition());
            assertTrue(c.reads.visible(c.r.owner, List.of(hit)).isEmpty());
            assertTrue(c.reads.readableInvocations(c.r.owner, Map.of(record.invocationId(), child)).isEmpty());
            assertThrows(AdmissionException.class, () -> c.queries.status("run", c.context).block(WAIT));
        }
    }

    @Test
    public void toolProposalCannotMasqueradeAsFinalAnswerAndDoesNotReleaseGate() {
        try (var c = new Chat()) {
            c.accept();
            var child = c.finalModel(ModelResult.FinishReason.TOOL_CALLS);
            assertEquals(INVALID_STATE, c.r.advance(c.finish(ToolChatRun.State.SUCCEEDED, child.result())).code());
            assertEquals(ToolChatRun.State.RUNNING, c.r.run().state());
            assertNotNull(c.gate());
            assertNull(c.turn().selectedCandidate());
            assertEquals(2, c.r.count("arte_ai_tool_chat_run_event"));
        }
    }

    @Test
    public void wrongResultOwnerOrParentAndMissingBytesCannotCompleteRun() {
        for (String mutation : List.of("UPDATE arte_ai_result SET owner_key='wrong'", "DELETE FROM arte_ai_result")) {
            try (var c = new Chat()) {
                c.accept();
                var child = c.finalModel(ModelResult.FinishReason.STOP);
                c.r.jdbc.update(mutation);
                assertEquals(INVALID_STATE, c.r.advance(c.finish(ToolChatRun.State.SUCCEEDED, child.result())).code());
                assertNotNull(c.gate());
                assertEquals(1, c.version());
            }
        }
    }

    @Test
    public void finalTransactionFailureDoesNotLeakSelectedAnswerAndRetrySucceeds() {
        try (var c = new Chat()) {
            c.accept();
            var child = c.finalModel(ModelResult.FinishReason.STOP);
            c.r.jdbc.execute("ALTER TABLE arte_ai_tool_chat_run_event ADD CONSTRAINT fail_final CHECK (sequence_no < 3)");
            var command = c.finish(ToolChatRun.State.SUCCEEDED, child.result());
            assertThrows(RuntimeException.class, () -> c.r.advance(command));
            assertNull(c.turn().selectedCandidate());
            assertNotNull(c.gate());
            assertEquals(1, c.version());
            assertNull(c.r.store.link(c.r.owner, "run").block(WAIT).finalInvocationId());
            assertEquals(1, c.r.count("arte_ai_tool_chat_run_command"));
            c.r.jdbc.execute("ALTER TABLE arte_ai_tool_chat_run_event DROP CONSTRAINT fail_final");
            assertEquals(APPLIED, c.r.advance(command).code());
            assertNull(c.gate());
        }
    }

    @Test
    public void failedRunReleasesGateAndIsNotAddedToHistory() {
        try (var c = new Chat()) {
            c.accept();
            c.r.advance(c.r.model(c.input, c.r.claim(), "model"));
            var cancelled = c.finish(ToolChatRun.State.CANCELLED, null);
            assertEquals(APPLIED, c.r.advance(cancelled).code());
            assertNull(c.gate());
            assertTrue(c.load().messages().isEmpty());
            assertThrows(AdmissionException.class, () -> c.queries.result("run", c.context).block(WAIT));
        }
    }

    @Test
    public void failedRegenerationKeepsPreviousRunAnswerAndDoesNotDuplicateUserFact() {
        try (var c = new Chat()) {
            c.accept();
            var child = c.finalModel(ModelResult.FinishReason.STOP);
            assertEquals(APPLIED, c.r.advance(c.finish(ToolChatRun.State.SUCCEEDED, child.result())).code());
            var previous = c.turn();
            var input = c.r.input("run2", "regenerate");
            var candidates = new ArrayList<>(previous.candidates());
            candidates.add(AnswerCandidate.run("run2"));
            var turn = Turn.typed(previous.turnId(), previous.conversationId(), previous.sequence(), previous.parentTurnId(), previous.supersedesTurnId(),
                    previous.userMessage(), candidates, previous.effectiveCandidate(), previous.version() + 1, previous.createdAt(), Instant.now());
            assertEquals(APPLIED, c.r.store.acceptChat(input, turn, c.version(), ignored -> c.r.authorize()).block(WAIT).code());
            assertEquals(2, c.r.count("arte_ai_conversation_message_fact"));
            var cancel = new ToolChatCommands.Advance(c.r.claim(), 0, "cancel-second", ToolChatRun.State.CANCELLED, FINISHED, 0, List.of(), null, null, null);
            assertEquals(APPLIED, c.r.advance(cancel).code());
            assertEquals(AnswerCandidate.run("run"), c.turn().selectedCandidate());
            assertTrue(c.turn().invocationIds().isEmpty());
            assertEquals(2, c.load().messages().size());
        }
    }

    @Test
    public void renamePreservesRunGateAndMetadataBackfillRestoresOnlyFinalAnswerFact() {
        try (var c = new Chat()) {
            c.accept();
            long version = c.executions.conversationMetadata(c.r.owner, "conversation").block(WAIT).metadataVersion();
            assertTrue(c.executions.changeConversationMetadata(c.r.owner, new ConversationList.Command("conversation", version, "renamed", null, "rename")).block(WAIT).successful());
            assertEquals("TOOL_CHAT_RUN", c.r.jdbc.queryForObject("SELECT active_kind FROM arte_ai_conversation_new", String.class));
            assertEquals(key("run"), c.gate());
            var child = c.finalModel(ModelResult.FinishReason.STOP);
            assertTrue(c.r.advance(c.finish(ToolChatRun.State.SUCCEEDED, child.result())).successful());
            c.r.jdbc.update("DELETE FROM arte_ai_conversation_message_fact WHERE message_role='ASSISTANT'");
            c.r.jdbc.update("INSERT INTO arte_ai_conversation_projection_job(id_key,turn_sequence,invocation_index,event_sequence,incomplete) VALUES(?,0,0,0,FALSE)", key("conversation"));
            for (int i = 0; i < 4; i++) c.executions.backfillConversationMetadata(10).block(WAIT);
            assertEquals(2, c.r.count("arte_ai_conversation_message_fact"));
            assertEquals("renamed", c.executions.findConversation(c.r.owner, "conversation").block(WAIT).title());
        }
    }

    @Test
    public void runOutboxHasTypedTargetIndependentCursorAndSafeGenericLeaseLifecycle() {
        try (var c = new Chat()) {
            c.accept();
            assertTrue(c.executions.claim(OutboxMessage.Kind.EVENT, "events", Duration.ofSeconds(10), 10).block(WAIT).isEmpty());
            assertTrue(c.executions.claim(OutboxMessage.Kind.DISPATCH, "dispatch", Duration.ofSeconds(10), 10).block(WAIT).isEmpty());
            var event = c.executions.claim(OutboxMessage.Kind.RUN_EVENT, "runs", Duration.ofSeconds(10), 10).block(WAIT).getFirst();
            assertEquals(AnswerCandidate.run("run"), event.target());
            assertEquals("run", event.executionId());
            assertThrows(IllegalArgumentException.class, event::invocationId);
            assertTrue(c.executions.validateClaim(event).block(WAIT).successful());
            assertTrue(c.executions.renewClaim(event, Duration.ofSeconds(10)).block(WAIT).successful());
            assertEquals(APPLIED, c.executions.acknowledge(event).block(WAIT).code());
            assertEquals(REPLAYED, c.executions.acknowledge(event).block(WAIT).code());
            assertEquals(1, c.queries.events("run", 0, 10, c.context).block(WAIT).size());
            assertTrue(c.queries.events("run", 1, 10, c.context).block(WAIT).isEmpty());
            assertThrows(IllegalArgumentException.class, () -> c.queries.events("run", 2, 10, c.context).block(WAIT));
            assertNull(c.r.store.find(new ExecutionOwner("tenant", "workspace", "other"), "run").block(WAIT));
        }
    }
}
