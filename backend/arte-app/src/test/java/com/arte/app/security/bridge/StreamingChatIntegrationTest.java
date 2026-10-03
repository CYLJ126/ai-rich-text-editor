package com.arte.app.security.bridge;

import com.arte.ai.api.context.ContextService;
import com.arte.ai.api.conversation.*;
import com.arte.ai.context.ConservativeTokenEstimator;
import com.arte.ai.model.budget.BudgetQuote;
import com.arte.ai.model.execution.ExecutionStatus;
import com.arte.ai.spi.adapter.ConnectionRuntime;
import com.arte.app.ainew.*;
import com.arte.base.exception.BaseException;
import com.arte.base.model.execution.*;
import com.arte.base.spi.observability.Telemetry;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static com.arte.app.security.bridge.SecurityBridgeFixture.*;

class StreamingChatIntegrationTest {
    DurableModelWorkerIntegrationTest d;
    NewChatIntegrationTest f;
    volatile boolean truncate;
    volatile CountDownLatch block;

    @BeforeEach
    void setup() throws Exception {
        d = new DurableModelWorkerIntegrationTest();
        d.setup();
        f = d.f;
        var runtime = new ConnectionRuntime() {
            public byte[] exchange(com.arte.ai.model.definition.ConnectionDefinition connection, byte[] body, com.arte.base.execution.ExecutionCheckpoint checkpoint) {
                throw new AssertionError("must use actual streaming protocol");
            }

            public void exchangeStream(com.arte.ai.model.definition.ConnectionDefinition connection, byte[] body, com.arte.base.execution.ExecutionCheckpoint checkpoint, ChunkConsumer consumer) throws Exception {
                int call = f.calls.incrementAndGet();
                f.requests.add(new String(body, StandardCharsets.UTF_8));
                String first = "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"答复-" + call + "😀\"},\"finish_reason\":null}]}\n\n";
                byte[] bytes = first.getBytes(StandardCharsets.UTF_8);
                // 每个网络片段只有一个字节，涵盖中文字和 emoji 被拆开。
                for (int i = 0; i < bytes.length; i++) consumer.accept(bytes, i, 1);
                if (block != null) try (var stop = checkpoint.onStop(block::countDown)) {
                    assertTrue(block.await(4, TimeUnit.SECONDS));
                    checkpoint.check();
                }
                if (truncate) return;
                bytes = ("data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":4}}\n\ndata: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
                consumer.accept(bytes, 0, bytes.length);
            }
        };
        f.provider = new CompatibleChatProviderAdapter(runtime, "test", BigDecimal.ZERO, BigDecimal.ZERO, new BudgetQuote(BigDecimal.ONE, "USD"), 16384, 10);
        d.wire(d.queue);
        var contexts = new ContextService(f.chatStore, f.coordinator, f.clock, 8192, 32, Duration.ofMinutes(10), 384, 64, new ConservativeTokenEstimator());
        f.conversations = new ConversationService(f.chatStore, f.access, f.clock);
        var chats = new ChatService(f.conversations, contexts, f.chatStore, f.coordinator, f.definitions.capabilityRef(), f.clock, 10, Telemetry.disabled(), true);
        f.service = new NewChatCallService(f.conversations, chats, f.contexts, f.consents, f.definitions, "new-ai", f.manager);
    }

    @AfterEach
    void cleanup() throws Exception {
        if (block != null) block.countDown();
        d.cleanup();
    }

    @Test
    void livePartialIsDurableRefreshableAndReplayedWithoutDuplication() throws Exception {
        block = new CountDownLatch(1);
        var conversation = f.create();
        var accepted = f.submit(conversation, "问题一", "one");
        d.start();
        DurableModelWorkerIntegrationTest.await(() -> !f.executions.find(f.scope, accepted.execution().executionId()).orElseThrow().partialText().isEmpty());
        var observed = f.service.observe(f.http, TENANT, WORKSPACE, conversation.conversationId(), accepted.turn().turnId());
        var batch = f.service.events(observed, -1, 64);
        var delta = batch.stream().filter(e -> e.payload().textDelta() != null).findFirst().orElseThrow();
        assertEquals("答复-1😀", delta.payload().textDelta());
        var refreshed = f.service.history(f.http, TENANT, WORKSPACE, conversation.conversationId(), Long.MAX_VALUE, 10).getFirst();
        assertEquals(ExecutionStatus.RUNNING, refreshed.execution().status());
        assertEquals(delta.sequence(), refreshed.execution().partialSequence());
        assertEquals("答复-1😀", refreshed.execution().partialText());
        assertTrue(f.service.events(observed, delta.sequence(), 64).isEmpty());
        block.countDown();
        var done = f.finished(conversation, accepted.turn().turnId());
        assertEquals(ExecutionStatus.SUCCEEDED, done.execution().status());
        assertEquals("答复-1😀", ((com.arte.ai.model.message.TextPart) done.execution().result().output().getFirst()).text());
        var rest = f.service.events(observed, delta.sequence(), 64);
        assertEquals(1, rest.size());
        assertEquals(ExecutionStatus.SUCCEEDED, rest.getFirst().payload().status());
        assertEquals(0, d.reserved().compareTo(BigDecimal.ZERO));
        assertTrue(f.requests.getFirst().contains("\"stream\":true"));
        assertTrue(f.requests.getFirst().contains("\"include_usage\":true"));
    }

    @Test
    void incompleteStreamKeepsPartialUnknownCostAndExcludesItFromNextContext() throws Exception {
        truncate = true;
        var conversation = f.create();
        d.start();
        var first = f.finished(conversation, f.submit(conversation, "中断问题", "one").turn().turnId());
        assertEquals(ExecutionStatus.OUTCOME_UNKNOWN, first.execution().status());
        assertEquals("答复-1😀", first.execution().partialText());
        assertNull(first.execution().result());
        assertEquals(0, d.reserved().compareTo(BigDecimal.ONE));
        truncate = false;
        var next = f.finished(conversation, f.submit(f.current(conversation), "新问题", "two").turn().turnId());
        assertEquals(ExecutionStatus.SUCCEEDED, next.execution().status());
        assertFalse(f.requests.getLast().contains("答复-1"));
        assertTrue(f.chatStore.snapshot(f.scope, next.turn().contextSnapshotId()).orElseThrow().history().isEmpty());
    }

    @Test
    void tokenWindowTrimsOldestWholePairAndKeepsLatestSuccessfulAnswer() throws Exception {
        var conversation = f.create();
        d.start();
        var first = f.finished(conversation, f.submit(conversation, "问题一", "one").turn().turnId());
        var second = f.finished(conversation, f.submit(f.current(conversation), "问题二", "two").turn().turnId());
        var third = f.finished(conversation, f.submit(f.current(conversation), "问题三", "three").turn().turnId());
        var snapshot = f.chatStore.snapshot(f.scope, third.turn().contextSnapshotId()).orElseThrow();
        assertEquals(3, snapshot.messages().size());
        assertEquals(1, snapshot.history().size());
        assertEquals(second.turn().turnId(), snapshot.history().getFirst().turnId());
        assertNotEquals(first.turn().turnId(), snapshot.history().getFirst().turnId());
        var budget = snapshot.budget();
        assertEquals(384, budget.contextWindowTokens());
        assertEquals(10, budget.outputTokenReserve());
        assertEquals(64, budget.safetyTokenReserve());
        assertEquals(310, budget.inputTokenLimit());
        assertEquals(new ConservativeTokenEstimator().estimate(snapshot.messages()), budget.estimatedInputTokens());
        assertTrue(f.requests.getLast().contains("答复-2"));
        assertFalse(f.requests.getLast().contains("答复-1"));
    }

    @Test
    void oversizedCurrentQuestionFailsBeforeBudgetReservationOrDispatch() {
        assertThrows(BaseException.class, () -> f.submit(f.create(), "中".repeat(100), "large"));
        assertEquals(0, d.count("arte_ai_new_execution"));
        assertEquals(0, f.calls.get());
        assertEquals(0, d.reserved().signum());
    }

    @Test
    void tokenBudgetFactsAreProtectedBySnapshotDigest() throws Exception {
        var conversation = f.create();
        d.start();
        var done = f.finished(conversation, f.submit(conversation, "问题", "one").turn().turnId());
        f.jdbc.update("UPDATE arte_ai_new_context_token_budget SET estimated_input_tokens=0 WHERE snapshot_id=?", done.turn().contextSnapshotId());
        assertThrows(BaseException.class, () -> f.chatStore.snapshot(f.scope, done.turn().contextSnapshotId()));
    }

    @Test
    void cancellationClosesStreamingIoAndPreservesOnlyIncompleteAnswer() throws Exception {
        block = new CountDownLatch(1);
        var conversation = f.create();
        var accepted = f.submit(conversation, "取消", "one");
        d.start();
        DurableModelWorkerIntegrationTest.await(() -> !f.executions.find(f.scope, accepted.execution().executionId()).orElseThrow().partialText().isEmpty());
        assertEquals(CancellationStatus.CANCELLING, f.service.cancel(f.http, TENANT, WORKSPACE, conversation.conversationId(), accepted.turn().turnId()));
        var done = f.finished(conversation, accepted.turn().turnId());
        assertEquals(ExecutionStatus.OUTCOME_UNKNOWN, done.execution().status());
        assertEquals("答复-1😀", done.execution().partialText());
        assertNull(done.execution().result());
        assertEquals(0, d.reserved().compareTo(BigDecimal.ONE));
    }

    @Test
    void staleLeaseCannotAppendDeltaAfterRecovery() {
        var accepted = f.submit(f.create(), "租约", "one");
        var lease = d.queue.claim().orElseThrow();
        var store = d.queue.fenced(lease);
        assertTrue(store.start(f.scope, accepted.execution().executionId()));
        store.markDispatched(f.scope, accepted.execution().executionId());
        store.appendDelta(f.scope, accepted.execution().executionId(), "first");
        d.expireOwner();
        var replacement = d.queue(2, 2, 100);
        assertTrue(replacement.acquire());
        replacement.recover();
        assertThrows(BaseException.class, () -> store.appendDelta(f.scope, accepted.execution().executionId(), "stale"));
        var result = f.executions.find(f.scope, accepted.execution().executionId()).orElseThrow();
        assertEquals(ExecutionStatus.OUTCOME_UNKNOWN, result.status());
        assertEquals("first", result.partialText());
    }

    @Test
    void deltaCacheAndEventRollbackTogetherWhenDeltaInsertFails() {
        var accepted = f.submit(f.create(), "原子", "one");
        var lease = d.queue.claim().orElseThrow();
        var store = d.queue.fenced(lease);
        assertTrue(store.start(f.scope, accepted.execution().executionId()));
        store.markDispatched(f.scope, accepted.execution().executionId());
        store.appendDelta(f.scope, accepted.execution().executionId(), "first");
        int events = d.count("arte_ai_new_event");
        f.jdbc.execute("ALTER TABLE arte_ai_new_stream_delta ADD CONSTRAINT reject_delta CHECK(text_delta <> 'rejected')");
        assertThrows(RuntimeException.class, () -> store.appendDelta(f.scope, accepted.execution().executionId(), "rejected"));
        assertEquals(events, d.count("arte_ai_new_event"));
        assertEquals("first", f.executions.find(f.scope, accepted.execution().executionId()).orElseThrow().partialText());
    }

    @Test
    void revokedReadPermissionBlocksReplayOfAlreadyPersistedPartial() throws Exception {
        block = new CountDownLatch(1);
        var conversation = f.create();
        var accepted = f.submit(conversation, "撤销", "one");
        d.start();
        DurableModelWorkerIntegrationTest.await(() -> !f.executions.find(f.scope, accepted.execution().executionId()).orElseThrow().partialText().isEmpty());
        var observation = f.service.observe(f.http, TENANT, WORKSPACE, conversation.conversationId(), accepted.turn().turnId());
        f.jdbc.update("UPDATE arte_security_member SET enabled=FALSE WHERE user_id='1'");
        assertThrows(BaseException.class, () -> f.service.events(observation, -1, 64));
    }

    @Test
    void httpSseReplaysPersistedTextAndTerminalAfterReload() throws Exception {
        var conversation = f.create();
        d.start();
        var done = f.finished(conversation, f.submit(conversation, "HTTP补读", "one").turn().turnId());
        try (var streams = new ChatEventStreams(f.service, 2, Duration.ofSeconds(10), f.executions)) {
            var web = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new NewChatController(f.service, streams)).build();
            var request = web.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                    "/api/ai-new/conversations/" + conversation.conversationId() + "/turns/" + done.turn().turnId() + "/events")
                            .param("tenantId", TENANT).param("workspaceId", WORKSPACE).param("after", "1"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.request().asyncStarted()).andReturn();
            request.getAsyncResult(3000);
            var response = web.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(request))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andReturn().getResponse();
            String data = response.getContentAsString(StandardCharsets.UTF_8);
            assertTrue(data.contains("event:model"));
            assertTrue(data.contains("答复-1😀"));
            assertTrue(data.contains("SUCCEEDED"));
            assertFalse(data.contains("\"status\":\"ACCEPTED\""));
            assertEquals(1, f.calls.get());
        }
    }

    @Test
    void subscriptionCapacityIsBoundedAndDoesNotReserveMoreModelBudget() {
        var conversation = f.create();
        var accepted = f.submit(conversation, "订阅", "one");
        var observation = f.service.observe(f.http, TENANT, WORKSPACE, conversation.conversationId(), accepted.turn().turnId());
        try (var streams = new ChatEventStreams(f.service, 2, Duration.ofSeconds(10))) {
            streams.open(observation, -1);
            streams.open(observation, -1);
            assertThrows(BaseException.class, () -> streams.open(observation, -1));
            assertThrows(IllegalArgumentException.class, () -> streams.open(observation, -2));
            assertEquals(0, d.reserved().compareTo(BigDecimal.ONE));
            assertEquals(0, f.calls.get());
            streams.close();
            assertThrows(IllegalStateException.class, () -> streams.open(observation, -1));
        }
    }
}
