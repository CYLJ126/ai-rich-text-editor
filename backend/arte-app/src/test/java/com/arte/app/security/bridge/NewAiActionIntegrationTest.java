package com.arte.app.security.bridge;

import com.arte.ai.api.action.AiActionService;
import com.arte.ai.model.action.AiActionExecution;
import com.arte.ai.model.action.AiActionResult;
import com.arte.ai.model.execution.ExecutionStatus;
import com.arte.ai.model.generation.ModelOptions;
import com.arte.ai.model.message.TextPart;
import com.arte.ai.spi.store.AiActionStore;
import com.arte.app.ainew.*;
import com.arte.app.testsupport.MySqlTestScripts;
import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.CancellationStatus;
import com.arte.base.model.identity.ExecutionScope;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.arte.app.security.bridge.SecurityBridgeFixture.TENANT;
import static com.arte.app.security.bridge.SecurityBridgeFixture.WORKSPACE;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实身份、动作表、预算账本与耐久 Worker；模型使用分块 SSE 协议替身。
 */
class NewAiActionIntegrationTest {
    StreamingChatIntegrationTest streaming;
    NewChatIntegrationTest f;
    JdbcAiActionStore store;
    AiActionService core;
    NewAiActionCallService service;

    @BeforeEach
    void setup() throws Exception {
        streaming = new StreamingChatIntegrationTest();
        streaming.setup();
        f = streaming.f;
        ScriptUtils.executeSqlScript(f.schemaConnection, MySqlTestScripts.h2Resource(
                Files.readString(Path.of("scripts/arte-ai-new-action-ddl-mysql.sql"))));
        store = new JdbcAiActionStore(f.jdbc, f.manager);
        wire(store);
    }

    private AiActionService core(AiActionStore storage) {
        return new AiActionService(storage, f.coordinator, f.definitions.capabilityRef(), f.definitions.bindingRef(),
                f.clock, 8192, 10, 8192, 256, true);
    }

    private void wire(AiActionStore storage) {
        core = core(storage);
        service = new NewAiActionCallService(core, f.contexts, f.consents, f.definitions, "new-ai", f.manager);
    }

    @AfterEach
    void cleanup() throws Exception {
        streaming.cleanup();
    }

    private AiActionResult submit(String key) {
        return service.submit(f.http, TENANT, WORKSPACE, "rewrite", "待改写的中文原文", "更简洁，保留事实", null, key, true);
    }

    private AiActionResult find(String id) {
        return service.find(f.http, TENANT, WORKSPACE, id);
    }

    private AiActionResult finished(String id) throws Exception {
        DurableModelWorkerIntegrationTest.await(() -> {
            var result = find(id);
            return result.execution() != null && result.execution().status() != ExecutionStatus.ACCEPTED && result.execution().status() != ExecutionStatus.RUNNING;
        });
        return find(id);
    }

    @Test
    void independentRewriteIsDurableAndDoesNotCreateAConversation() throws Exception {
        var accepted = submit("one");
        assertEquals(ExecutionStatus.ACCEPTED, accepted.execution().status());
        assertEquals(AiActionService.REWRITE, accepted.action().actionRef());
        assertEquals(10, accepted.action().modelOptions().maxOutputTokens());
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_conversation", Integer.class));
        streaming.d.start();
        var done = finished(accepted.action().actionExecutionId());
        assertEquals(ExecutionStatus.SUCCEEDED, done.execution().status());
        assertTrue(f.requests.getFirst().contains("待改写的中文原文"));
        assertTrue(f.requests.getFirst().contains("更简洁，保留事实"));
        assertTrue(f.requests.getFirst().contains("\"role\":\"system\""));
        assertEquals("答复-1😀", ((TextPart) done.execution().result().output().getFirst()).text());
        wire(new JdbcAiActionStore(f.jdbc, f.manager));
        var replay = submit("one");
        assertEquals(done.action(), replay.action());
        assertEquals(done.execution().executionId(), replay.execution().executionId());
        assertEquals(1, f.calls.get());
    }

    @Test
    void regenerationUsesFixedOriginalInputAndANewExecution() throws Exception {
        streaming.d.start();
        var original = finished(submit("one").action().actionExecutionId());
        var id = original.action().actionExecutionId();
        var regenerated = service.regenerate(f.http, TENANT, WORKSPACE, id, new ModelOptions(0.4, 8), "regen", true);
        var done = finished(regenerated.action().actionExecutionId());
        assertNotEquals(original.execution().executionId(), done.execution().executionId());
        assertNotEquals(id, done.action().actionExecutionId());
        assertEquals(id, done.action().regeneratesActionId());
        assertEquals(original.action().input(), done.action().input());
        assertEquals(8, done.action().modelOptions().maxOutputTokens());
        assertFalse(f.requests.getLast().contains("答复-1"));
        assertEquals(done.action(), service.regenerate(f.http, TENANT, WORKSPACE, id, new ModelOptions(0.4, 8), "regen", true).action());
        assertEquals(2, f.calls.get());
        var conflict = assertThrows(BaseException.class, () -> service.regenerate(f.http, TENANT, WORKSPACE, id, null, "regen", true));
        assertEquals(CommonErrorCode.IDEMPOTENCY_CONFLICT.code(), conflict.error().code());
    }

    @Test
    void partialOutputSurvivesObserverRestartAndUsesExclusiveCursor() throws Exception {
        streaming.block = new CountDownLatch(1);
        var accepted = submit("partial");
        String id = accepted.action().actionExecutionId();
        streaming.d.start();
        DurableModelWorkerIntegrationTest.await(() -> !f.executions.find(f.scope, accepted.execution().executionId()).orElseThrow().partialText().isEmpty());
        wire(new JdbcAiActionStore(f.jdbc, f.manager));
        var observation = service.observe(f.http, TENANT, WORKSPACE, id);
        var refreshed = find(id).execution();
        assertEquals("答复-1😀", refreshed.partialText());
        var delta = service.events(observation, -1, 64).stream().filter(e -> e.payload().textDelta() != null).findFirst().orElseThrow();
        assertEquals(refreshed.partialSequence(), delta.sequence());
        assertTrue(service.events(observation, delta.sequence(), 64).isEmpty());
        streaming.block.countDown();
        assertEquals(ExecutionStatus.SUCCEEDED, finished(id).execution().status());
        var rest = service.events(observation, delta.sequence(), 64);
        assertEquals(1, rest.size());
        assertEquals(ExecutionStatus.SUCCEEDED, rest.getFirst().payload().status());
        assertEquals(1, f.calls.get());
    }

    @Test
    void acceptedResponseLossIsRecoveredWithoutDispatchingTwice() throws Exception {
        var failingRead = new AiActionStore() {
            public AiActionExecution claim(AiActionExecution draft) {
                return store.claim(draft);
            }

            public Optional<AiActionExecution> findIdempotent(ExecutionScope scope, String key) {
                return store.findIdempotent(scope, key);
            }

            public Optional<AiActionExecution> find(ExecutionScope scope, String id) {
                throw new IllegalStateException("simulated response loss");
            }
        };
        wire(failingRead);
        assertThrows(IllegalStateException.class, () -> submit("lost"));
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_execution", Integer.class));
        wire(new JdbcAiActionStore(f.jdbc, f.manager));
        var recovered = submit("lost");
        streaming.d.start();
        finished(recovered.action().actionExecutionId());
        assertEquals(1, f.calls.get());
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_action", Integer.class));
    }

    @Test
    void concurrentSameKeyAcrossServicesOnlyReservesAndDispatchesOnce() throws Exception {
        var other = core(new JdbcAiActionStore(f.jdbc, f.manager));
        var viewer = f.viewer();
        var gate = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            Callable<AiActionResult> first = () -> concurrentSubmit(core, viewer, gate);
            Callable<AiActionResult> second = () -> concurrentSubmit(other, viewer, gate);
            var one = pool.submit(first);
            var two = pool.submit(second);
            gate.countDown();
            var a = one.get(5, TimeUnit.SECONDS);
            var b = two.get(5, TimeUnit.SECONDS);
            assertEquals(a.action().actionExecutionId(), b.action().actionExecutionId());
            assertEquals(a.execution().executionId(), b.execution().executionId());
            assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_execution", Integer.class));
            streaming.d.start();
            finished(a.action().actionExecutionId());
            assertEquals(1, f.calls.get());
        }
    }

    private AiActionResult concurrentSubmit(AiActionService actions, com.arte.base.model.execution.ExecutionContext viewer,
                                            CountDownLatch gate) throws Exception {
        f.login("alice", 1);
        try {
            gate.await();
            return actions.submit(viewer, "rewrite", "同一原文", "简洁", null, "race", prepared -> f.consents.confirm(f.http, prepared));
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Test
    void differentInputConflictsAndInvalidRequestsNeverReserveBudget() {
        submit("one");
        var conflict = assertThrows(BaseException.class, () -> service.submit(f.http, TENANT, WORKSPACE, "rewrite", "不同原文", null, null, "one", true));
        assertEquals(CommonErrorCode.IDEMPOTENCY_CONFLICT.code(), conflict.error().code());
        assertThrows(IllegalArgumentException.class, () -> service.submit(f.http, TENANT, WORKSPACE, "rewrite", " ", null, null, "blank", true));
        assertThrows(BaseException.class, () -> service.submit(f.http, TENANT, WORKSPACE, "rewrite", "中".repeat(3000), null, null, "oversize", true));
        assertThrows(BaseException.class, () -> service.submit(f.http, TENANT, WORKSPACE, "unknown", "原文", null, null, "unknown", true));
        assertThrows(IllegalArgumentException.class, () -> service.submit(f.http, TENANT, WORKSPACE, "rewrite", "原文", null, new ModelOptions(null, 11), "tokens", true));
        assertThrows(org.springframework.security.access.AccessDeniedException.class, () -> service.submit(f.http, TENANT, WORKSPACE, "rewrite", "原文", null, null, "denied", false));
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_action", Integer.class));
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_execution", Integer.class));
        assertEquals(0, f.calls.get());
    }

    @Test
    void fixedInputTamperingIsRejectedOnReadAndReplay() {
        var accepted = submit("one");
        String id = accepted.action().actionExecutionId();
        var payload = JsonParser.parseString(f.jdbc.queryForObject("SELECT payload_json FROM arte_ai_new_action WHERE action_id=?", String.class, id)).getAsJsonObject();
        payload.getAsJsonArray("messages").get(1).getAsJsonObject().getAsJsonArray("parts").get(0).getAsJsonObject().addProperty("text", "tampered");
        f.jdbc.update("UPDATE arte_ai_new_action SET payload_json=? WHERE action_id=?", payload.toString(), id);
        assertThrows(BaseException.class, () -> find(id));
        assertThrows(BaseException.class, () -> submit("one"));
        assertThrows(BaseException.class, () -> service.regenerate(f.http, TENANT, WORKSPACE, id, null, "regen", true));
        assertEquals(0, f.calls.get());
    }

    @Test
    void delimiterTextCannotAliasDifferentOriginalAndRequirements() {
        service.submit(f.http, TENANT, WORKSPACE, "rewrite", "bar\n\n原文：\nbaz", "foo", null, "delimiter", true);
        var conflict = assertThrows(BaseException.class, () -> service.submit(f.http, TENANT, WORKSPACE, "rewrite",
                "baz", "foo\n\n原文：\nbar", null, "delimiter", true));
        assertEquals(CommonErrorCode.IDEMPOTENCY_CONFLICT.code(), conflict.error().code());
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_execution", Integer.class));
    }

    @Test
    void tokenBudgetRejectsBeforePersistingOrReserving() {
        var constrained = new AiActionService(store, f.coordinator, f.definitions.capabilityRef(), f.definitions.bindingRef(),
                f.clock, 8192, 10, 800, 64, true);
        assertThrows(BaseException.class, () -> constrained.submit(f.viewer(), "rewrite", "a".repeat(1000), null, null, "tokens", request -> {
            fail("over-capacity input must not request consent");
            return null;
        }));
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_action", Integer.class));
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_execution", Integer.class));
    }

    @Test
    void failedConsentLeavesFixedInputRecoverableAndDoesNotDispatch() throws Exception {
        assertThrows(BaseException.class, () -> core.submit(f.viewer(), "rewrite", "待改写的中文原文", "更简洁，保留事实", null, "resume", request -> {
            throw com.arte.ai.conversation.ChatValues.failure(CommonErrorCode.UNAUTHORIZED, "test-consent");
        }));
        var original = store.findIdempotent(f.scope, "resume").orElseThrow();
        assertNull(find(original.actionExecutionId()).execution());
        assertThrows(BaseException.class, () -> service.cancel(f.http, TENANT, WORKSPACE, original.actionExecutionId()));
        assertEquals(0, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_execution", Integer.class));
        var resumed = submit("resume");
        assertEquals(original.actionExecutionId(), resumed.action().actionExecutionId());
        streaming.d.start();
        finished(resumed.action().actionExecutionId());
        assertEquals(1, f.calls.get());
    }

    @Test
    void otherPrincipalCannotQueryOrControlTheAction() {
        var accepted = submit("private");
        var caller = f.viewer();
        var bob = new com.arte.base.model.execution.ExecutionContext(new ExecutionScope(TENANT, WORKSPACE,
                new com.arte.base.model.identity.PrincipalRef("2", com.arte.base.model.identity.PrincipalType.USER)),
                caller.traceId(), null, caller.deadline(), null, caller.authorizationScopes(), null, null, null);
        String id = accepted.action().actionExecutionId();
        assertEquals(CommonErrorCode.NOT_FOUND.code(), assertThrows(BaseException.class, () -> core.find(bob, id)).error().code());
        assertThrows(BaseException.class, () -> core.cancel(bob, id));
        assertThrows(BaseException.class, () -> core.events(bob, id, -1, 64));
        assertThrows(BaseException.class, () -> core.regenerate(bob, id, null, "regen", request -> null));
    }

    @Test
    void unknownResultsRetainPartialAndCannotBeRegenerated() throws Exception {
        streaming.truncate = true;
        var accepted = submit("unknown");
        streaming.d.start();
        var done = finished(accepted.action().actionExecutionId());
        assertEquals(ExecutionStatus.OUTCOME_UNKNOWN, done.execution().status());
        assertNull(done.execution().result());
        assertEquals("答复-1😀", done.execution().partialText());
        assertThrows(BaseException.class, () -> service.regenerate(f.http, TENANT, WORKSPACE, done.action().actionExecutionId(), null, "regen", true));
        assertEquals(done.execution().executionId(), submit("unknown").execution().executionId());
        assertEquals(1, f.calls.get());
    }

    @Test
    void queuedCancellationDoesNotDispatchAndRunningRegenerationIsRejected() throws Exception {
        var accepted = submit("cancel");
        String id = accepted.action().actionExecutionId();
        assertThrows(BaseException.class, () -> service.regenerate(f.http, TENANT, WORKSPACE, id, null, "regen", true));
        var status = service.cancel(f.http, TENANT, WORKSPACE, id);
        assertTrue(status == CancellationStatus.CANCELLED || status == CancellationStatus.CANCELLING);
        streaming.d.start();
        assertEquals(ExecutionStatus.CANCELLED, finished(id).execution().status());
        assertEquals(0, f.calls.get());
    }

    @Test
    void revokedPermissionBlocksPartialReplayAndSubmission() throws Exception {
        streaming.block = new CountDownLatch(1);
        var accepted = submit("revoke");
        streaming.d.start();
        DurableModelWorkerIntegrationTest.await(() -> !f.executions.find(f.scope, accepted.execution().executionId()).orElseThrow().partialText().isEmpty());
        var observation = service.observe(f.http, TENANT, WORKSPACE, accepted.action().actionExecutionId());
        f.jdbc.update("UPDATE arte_security_member SET enabled=FALSE WHERE user_id='1'");
        assertThrows(BaseException.class, () -> service.events(observation, -1, 64));
        assertThrows(RuntimeException.class, () -> find(accepted.action().actionExecutionId()));
        assertThrows(RuntimeException.class, () -> submit("other"));
    }

    @Test
    void runningCancellationRetainsPartialWithoutPublishingASuccessfulResult() throws Exception {
        streaming.block = new CountDownLatch(1);
        var accepted = submit("running-cancel");
        String id = accepted.action().actionExecutionId();
        streaming.d.start();
        DurableModelWorkerIntegrationTest.await(() -> !f.executions.find(f.scope, accepted.execution().executionId()).orElseThrow().partialText().isEmpty());
        service.cancel(f.http, TENANT, WORKSPACE, id);
        var done = finished(id);
        assertEquals(ExecutionStatus.OUTCOME_UNKNOWN, done.execution().status());
        assertNull(done.execution().result());
        assertEquals("答复-1😀", done.execution().partialText());
        assertEquals(1, f.calls.get());
    }

    @Test
    void streamCapacityIsBoundedAndDisconnectDoesNotCancelGeneration() {
        var accepted = submit("subscriptions");
        var observation = service.observe(f.http, TENANT, WORKSPACE, accepted.action().actionExecutionId());
        try (var streams = new AiActionEventStreams(service, 2, Duration.ofSeconds(10), f.executions)) {
            streams.open(observation, -1);
            streams.open(observation, -1);
            assertThrows(BaseException.class, () -> streams.open(observation, -1));
            assertThrows(IllegalArgumentException.class, () -> streams.open(observation, -2));
        }
        assertEquals(ExecutionStatus.ACCEPTED, find(accepted.action().actionExecutionId()).execution().status());
        assertEquals(0, f.calls.get());
    }

    @Test
    void rawModelEntryCannotUseTheReservedActionIdentity() {
        var accepted = submit("reserved");
        var models = new NewModelCallService(f.coordinator, f.contexts, f.consents, f.definitions, "new-ai");
        var input = new com.arte.ai.model.generation.GenerationRequest(accepted.action().input(), accepted.action().modelOptions(), java.util.List.of(), null);
        assertThrows(IllegalArgumentException.class, () -> models.generate(f.http, TENANT, WORKSPACE, AiActionService.modelKey(accepted.action()), input, true));
        assertEquals(1, f.jdbc.queryForObject("SELECT COUNT(*) FROM arte_ai_new_execution", Integer.class));
    }

    @Test
    void httpEntrySupportsAcceptedStatusErrorsAndSseCursorReplay() throws Exception {
        try (var streams = new AiActionEventStreams(service, 2, Duration.ofSeconds(10), f.executions)) {
            var web = MockMvcBuilders.standaloneSetup(new NewAiActionController(service, streams)).build();
            String body = "{\"tenantId\":\"" + TENANT + "\",\"workspaceId\":\"" + WORKSPACE + "\",\"text\":\"原文\",\"requirements\":\"简洁\",\"externalTransferConfirmed\":true}";
            var response = web.perform(post("/api/ai-new/actions/rewrite/executions").header("Idempotency-Key", "http")
                    .contentType("application/json").content(body)).andExpect(status().isAccepted()).andReturn().getResponse();
            var data = JsonParser.parseString(response.getContentAsString()).getAsJsonObject();
            String id = data.getAsJsonObject("action").get("actionExecutionId").getAsString();
            assertEquals("/api/ai-new/actions/executions/" + id, response.getHeader("Location"));
            web.perform(post("/api/ai-new/actions/rewrite/executions").header("Idempotency-Key", "http")
                    .contentType("application/json").content(body.replace("原文", "different"))).andExpect(status().isConflict());
            web.perform(post("/api/ai-new/actions/rewrite/executions").header("Idempotency-Key", "denied")
                    .contentType("application/json").content(body.replace("true", "false"))).andExpect(status().isForbidden());
            web.perform(get("/api/ai-new/actions/executions/missing").param("tenantId", TENANT).param("workspaceId", WORKSPACE)).andExpect(status().isNotFound());
            streaming.d.start();
            finished(id);
            var async = web.perform(get("/api/ai-new/actions/executions/" + id + "/events").param("tenantId", TENANT)
                            .param("workspaceId", WORKSPACE).param("after", "999").header("Last-Event-ID", "1"))
                    .andExpect(request().asyncStarted()).andReturn();
            async.getAsyncResult(3000);
            var replay = web.perform(asyncDispatch(async)).andExpect(status().isOk()).andReturn().getResponse();
            String events = replay.getContentAsString(StandardCharsets.UTF_8);
            assertTrue(events.contains("答复-1😀"));
            assertTrue(events.contains("SUCCEEDED"));
            assertFalse(events.contains("\"status\":\"ACCEPTED\""));
            assertEquals("no", replay.getHeader("X-Accel-Buffering"));
            assertEquals(1, f.calls.get());
        }
    }
}
