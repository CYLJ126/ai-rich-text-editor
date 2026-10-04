package com.arte.app.security.bridge;

import com.arte.ai.api.action.AiActionService;
import com.arte.ai.api.context.ResourceContextService;
import com.arte.ai.api.control.BindingManager;
import com.arte.ai.api.control.CapabilityCatalog;
import com.arte.ai.api.control.ConnectionManager;
import com.arte.ai.api.execution.BudgetService;
import com.arte.ai.api.execution.InvocationCoordinator;
import com.arte.ai.context.ContextCapacityException;
import com.arte.ai.context.ResourceContextValues;
import com.arte.ai.execution.ModelBindingResolver;
import com.arte.ai.gateway.DefaultModelGateway;
import com.arte.ai.model.action.AiActionResult;
import com.arte.ai.model.budget.BudgetQuote;
import com.arte.ai.model.context.ResourceContextSelection;
import com.arte.ai.model.context.ResourceContextSnapshot;
import com.arte.ai.model.execution.ExecutionStatus;
import com.arte.ai.model.generation.ModelOptions;
import com.arte.ai.spi.store.AiActionStore;
import com.arte.app.ainew.*;
import com.arte.app.execution.support.JdbcAuditSink;
import com.arte.app.service.richtext.ArticleContextQueryService;
import com.arte.app.testsupport.MySqlTestScripts;
import com.arte.base.exception.BaseException;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.security.CommonResourceAction;
import com.arte.base.spi.observability.Telemetry;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;

import static com.arte.app.security.bridge.SecurityBridgeFixture.TENANT;
import static com.arte.app.security.bridge.SecurityBridgeFixture.WORKSPACE;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

/** 实际文章投影、授权、动作账本、耐久 Worker 和 SSE 替身的资料闭环。 */
class ResourceContextIntegrationTest {
    StreamingChatIntegrationTest streaming;
    NewChatIntegrationTest f;
    ArticleContextQueryService articles;
    ArticleResourceContextAdapter adapter;
    ResourceContextService contexts;
    JdbcAiActionStore store;
    AiActionService core;
    NewAiActionCallService service;
    NewResourceContextController metadata;
    static final String ORIGINAL = "甲😀乙，保留事实。这里是文章正文。";
    static final String REFERENCE = "参考资料：用于解释背景和核对事实。";

    @BeforeEach
    void setup() throws Exception {
        streaming = new StreamingChatIntegrationTest(); streaming.setup(); f = streaming.f;
        ScriptUtils.executeSqlScript(f.schemaConnection, MySqlTestScripts.h2Resource(Files.readString(Path.of("scripts/arte-ai-new-action-ddl-mysql.sql"))));
        f.jdbc.execute("ALTER TABLE arte_rt_article ADD title VARCHAR(256)");
        f.jdbc.execute("ALTER TABLE arte_rt_article ADD row_version INT");
        f.jdbc.execute("ALTER TABLE arte_rt_article ADD content_text CLOB");
        f.jdbc.update("UPDATE arte_rt_article SET title='文章',row_version=1,content_text=? WHERE id=10", ORIGINAL);
        f.jdbc.update("UPDATE arte_rt_article SET title='参考',row_version=1,content_text=? WHERE id=11", REFERENCE);
        f.policy(CommonResourceAction.READ.code()); f.policy(CommonResourceAction.EDIT.code());
        for (String id : List.of("10", "11")) {
            f.grant(id, 1, CommonResourceAction.AI_PROCESS); f.grant(id, 1, CommonResourceAction.EGRESS);
        }
        articles = new ArticleContextQueryService(f.jdbc);
        adapter = new ArticleResourceContextAdapter(articles, f.authorization);
        contexts = new ResourceContextService(List.of(adapter), f.clock, Duration.ofMinutes(10), 8192, 8192, 256);
        wire(contexts);
        metadata = new NewResourceContextController(articles, f.authorization, f.contexts, f.definitions, "new-ai");
    }

    void wire(ResourceContextService next) {
        contexts = next;
        f.coordinator = new InvocationCoordinator(new ModelBindingResolver(new CapabilityCatalog(f.definitions), new ConnectionManager(f.definitions), new BindingManager(f.definitions)),
                new DefaultModelGateway(List.of(f.provider)), new ExistingModelAccessPolicy(f.repository, f.clock, "new-ai"), f.egress, f.admission, f.tasks,
                f.executions, f.executions, new BudgetService(new BudgetQuote(BigDecimal.ONE, "USD"), f.executions),
                new JdbcAuditSink(f.jdbc, f.manager, f.clock), f.clock, Telemetry.disabled(), streaming.d.queue, contexts);
        store = new JdbcAiActionStore(f.jdbc, f.manager);
        core = new AiActionService(store, f.coordinator, f.definitions.capabilityRef(), f.definitions.bindingRef(), f.clock, 8192, 10, 8192, 256, true, contexts);
        service = calls(core);
    }

    NewAiActionCallService calls(AiActionService actions) {
        return new NewAiActionCallService(actions, f.contexts, f.consents, f.definitions, "new-ai", f.manager);
    }

    @AfterEach void cleanup() throws Exception { streaming.cleanup(); }

    ResourceContextSelection saved(String id) {
        return new ResourceContextSelection(metadata.article(f.http, id, TENANT, WORKSPACE).resource(), null);
    }
    ResourceContextSnapshot preview(ResourceContextSelection target, List<ResourceContextSelection> references) {
        return service.preview(f.http, TENANT, WORKSPACE, "rewrite", null, "简洁，保留事实", target, references, null);
    }
    AiActionResult submit(ResourceContextSelection target, List<ResourceContextSelection> references, String digest, String key) {
        return service.submit(f.http, TENANT, WORKSPACE, "rewrite", null, "简洁，保留事实", target, references, null, digest, key, true);
    }
    AiActionResult finished(String id) throws Exception {
        DurableModelWorkerIntegrationTest.await(() -> {
            var result = service.find(f.http, TENANT, WORKSPACE, id);
            return result.execution() != null && result.execution().status() != ExecutionStatus.ACCEPTED && result.execution().status() != ExecutionStatus.RUNNING;
        });
        return service.find(f.http, TENANT, WORKSPACE, id);
    }
    ExecutionContext viewer(ResourceContextSnapshot snapshot, boolean external) {
        var codes = new HashSet<String>(Set.of(CommonResourceAction.READ.code(), CommonResourceAction.AI_PROCESS.code()));
        if (external) codes.add(CommonResourceAction.EGRESS.code());
        var resources = new HashMap<ResourceRef, Set<String>>();
        for (var fragment : snapshot.fragments()) {
            var requested = new HashSet<>(codes);
            if (external && fragment.source().resource().isDraft()) requested.add(CommonResourceAction.EDIT.code());
            resources.put(fragment.source().resource(), Set.copyOf(requested)); codes.addAll(requested);
        }
        return f.contexts.create(f.http, TENANT, WORKSPACE, "new-ai", "chat", codes, resources);
    }

    @Test
    void previewShowsPinnedWholeArticleAndReferenceWithoutDispatching() throws Exception {
        var target = saved("10"); var reference = saved("11");
        assertEquals("1", target.resource().version());
        assertEquals(ResourceContextValues.textDigest(ORIGINAL), target.resource().contentDigest());
        var snapshot = preview(target, List.of(reference));
        assertEquals(2, snapshot.fragments().size());
        assertEquals("target", snapshot.fragments().getFirst().citationId());
        assertEquals("reference-1", snapshot.fragments().getLast().citationId());
        assertEquals(ORIGINAL, snapshot.fragments().getFirst().content());
        assertFalse(snapshot.fragments().getFirst().truncated());
        assertTrue(snapshot.fragments().getFirst().coverageDescription().contains("全文"));
        ResourceContextValues.verify(snapshot);
        assertEquals(0, count("arte_ai_new_execution")); assertEquals(0, count("arte_ai_new_action"));
        var accepted = submit(target, List.of(reference), snapshot.contentDigest(), "one");
        assertEquals(snapshot.contentDigest(), accepted.action().resourceContext().contentDigest());
        assertEquals(accepted.action().resourceContext(), accepted.execution().resourceContext());
        streaming.d.start(); var done = finished(accepted.action().actionExecutionId());
        assertEquals(ExecutionStatus.SUCCEEDED, done.execution().status());
        assertTrue(f.requests.getFirst().contains(ORIGINAL)); assertTrue(f.requests.getFirst().contains(REFERENCE));
        assertEquals(0, count("arte_ai_new_conversation"));
    }

    @Test
    void draftSelectionIsVerifiedAndOnlyTheSelectedRangeIsSent() throws Exception {
        String draft = "甲😀乙，用户刚修改的草稿。未选中的结尾内容";
        var resource = ResourceRef.draft("ARTICLE", "10", "1", "draft-1", ResourceContextValues.textDigest(draft)).withRange("utf16:1:4");
        var target = new ResourceContextSelection(resource, draft);
        var reference = saved("11");
        reference = new ResourceContextSelection(reference.resource().withRange("utf16:0:4"), null);
        var snapshot = preview(target, List.of(reference));
        var fragment = snapshot.fragments().getFirst();
        assertEquals("😀乙", fragment.content()); assertTrue(fragment.truncated());
        assertTrue(fragment.coverageDescription().contains("用户草稿选区"));
        assertEquals("draft-1", fragment.source().resource().draftId());
        var accepted = submit(target, List.of(reference), snapshot.contentDigest(), "draft");
        streaming.d.start(); finished(accepted.action().actionExecutionId());
        assertTrue(f.requests.getFirst().contains("😀乙"));
        assertFalse(f.requests.getFirst().contains("未选中的结尾内容"));
        assertFalse(f.requests.getFirst().contains("核对事实"));
        var payload = f.jdbc.queryForObject("SELECT payload_json FROM arte_ai_new_action", String.class);
        assertFalse(payload.contains("未选中的结尾内容"));
    }

    @Test
    void typedTextCanUseExplicitReferencesAndRemovingAReferenceChangesThePreview() {
        var reference = saved("11");
        var snapshot = service.preview(f.http, TENANT, WORKSPACE, "rewrite", "用户输入的原文", null, null, List.of(reference), null);
        assertEquals(1, snapshot.fragments().size());
        var rejected = assertThrows(BaseException.class, () -> service.submit(f.http, TENANT, WORKSPACE, "rewrite", "用户输入的原文", null,
                null, List.of(), null, snapshot.contentDigest(), "changed", true));
        assertEquals(CommonErrorCode.VERSION_CONFLICT.code(), rejected.error().code());
        assertEquals(0, count("arte_ai_new_execution"));
    }

    @Test
    void changedVersionAndChangedSameVersionContentCannotReplacePreviewedInput() {
        var target = saved("10"); var snapshot = preview(target, List.of());
        f.jdbc.update("UPDATE arte_rt_article SET row_version=2,content_text='新修改的内容' WHERE id=10");
        var conflict = assertThrows(BaseException.class, () -> submit(target, List.of(), snapshot.contentDigest(), "stale"));
        assertEquals(CommonErrorCode.VERSION_CONFLICT.code(), conflict.error().code());
        f.jdbc.update("UPDATE arte_rt_article SET row_version=1 WHERE id=10");
        assertThrows(BaseException.class, () -> preview(target, List.of()));
        assertEquals(0, count("arte_ai_new_action")); assertEquals(0, count("arte_ai_new_execution"));
    }

    @Test
    void forgedDraftHashAndReadOnlyDraftAreRejected() {
        var forged = new ResourceContextSelection(ResourceRef.draft("ARTICLE", "10", "1", "draft", ResourceContextValues.textDigest("真实草稿")), "伪造内容");
        assertThrows(BaseException.class, () -> preview(forged, List.of()));
        f.jdbc.update("UPDATE arte_rt_article SET create_by='bob',is_public=TRUE WHERE id=10");
        var readonly = new ResourceContextSelection(ResourceRef.draft("ARTICLE", "10", "1", "readonly", ResourceContextValues.textDigest("用户草稿")), "用户草稿");
        var denied = assertThrows(BaseException.class, () -> preview(readonly, List.of()));
        assertEquals(CommonErrorCode.UNAUTHORIZED.code(), denied.error().code());
        assertEquals(0, f.calls.get());
    }

    @Test
    void invalidRangesCannotSplitEmojiOrSelectOutsideTheFixedBody() {
        var original = saved("10");
        for (String range : List.of("utf16:1:2", "utf16:3:2", "utf16:0:999", "utf16:01:4", "node:abc")) {
            var selection = new ResourceContextSelection(original.resource().withRange(range), null);
            assertThrows(IllegalArgumentException.class, () -> preview(selection, List.of()), range);
        }
        var malformed = new ResourceContextSelection(ResourceRef.draft("ARTICLE", "10", "1", "invalid", ResourceContextValues.textDigest("x\uD800")), "x\uD800");
        assertThrows(IllegalArgumentException.class, () -> preview(malformed, List.of()));
    }

    @Test
    void duplicateUnsupportedAndUnpinnedSourcesAreNotAccepted() {
        var target = saved("10");
        assertThrows(IllegalArgumentException.class, () -> preview(target, List.of(target)));
        assertThrows(IllegalArgumentException.class, () -> new ResourceContextSelection(ResourceRef.current("ARTICLE", "10"), null));
        var unsupported = new ResourceContextSelection(new ResourceRef("CATALOG", "100", "1", null, null, ResourceContextValues.textDigest("catalog")), null);
        assertEquals(CommonErrorCode.UNSUPPORTED.code(), assertThrows(BaseException.class, () -> preview(unsupported, List.of())).error().code());
    }

    @Test
    void capacityFailureReportsBothUnitsAndDoesNotSilentlyCropTheReference() throws Exception {
        f.jdbc.update("UPDATE arte_rt_article SET content_text=? WHERE id=11", "中".repeat(4000));
        var target = saved("10"); var reference = saved("11");
        var capacity = assertThrows(ContextCapacityException.class, () -> preview(target, List.of(reference))).capacity();
        assertTrue(capacity.usedInputBytes() > capacity.inputByteLimit());
        assertTrue(capacity.estimatedInputTokens() > capacity.inputTokenLimit());
        assertEquals(0, count("arte_ai_new_execution"));
        try (var streams = new AiActionEventStreams(service, 2, Duration.ofSeconds(10), f.executions)) {
            var web = MockMvcBuilders.standaloneSetup(new NewAiActionController(service, streams)).build();
            var response = web.perform(post("/api/ai-new/actions/rewrite/context").contentType("application/json")
                    .content(body(target, List.of(reference), null))).andExpect(status().isBadRequest()).andReturn().getResponse();
            var error = JsonParser.parseString(response.getContentAsString()).getAsJsonObject();
            assertTrue(error.has("capacity")); assertTrue(error.getAsJsonObject("capacity").get("usedInputBytes").getAsInt() > 8192);
            assertFalse(response.getContentAsString().contains("中中中"));
        }
    }

    @Test
    void metadataAndPreviewRespectWorkspaceAndSeparateAiPermission() {
        f.jdbc.update("UPDATE arte_security_resource SET workspace_id='other' WHERE resource_id='11'");
        assertThrows(BaseException.class, () -> saved("11"));
        f.jdbc.update("UPDATE arte_security_resource SET workspace_id=? WHERE resource_id='11'", WORKSPACE);
        var target = saved("10");
        f.jdbc.update("DELETE FROM arte_security_resource_grant WHERE resource_id='10' AND action_code=?", CommonResourceAction.AI_PROCESS.code());
        assertNotNull(saved("10")); // 可读并不自动授予 AI 处理。
        assertThrows(BaseException.class, () -> preview(target, List.of()));
    }

    @Test
    void queueRechecksEgressGrantAndDoesNotDispatchAfterRevocation() throws Exception {
        var target = saved("10"); var snapshot = preview(target, List.of());
        var accepted = submit(target, List.of(), snapshot.contentDigest(), "revoked");
        f.jdbc.update("DELETE FROM arte_security_resource_grant WHERE resource_id='10' AND action_code=?", CommonResourceAction.EGRESS.code());
        streaming.d.start(); var done = finished(accepted.action().actionExecutionId());
        assertEquals(ExecutionStatus.FAILED, done.execution().status()); assertFalse(done.execution().dispatched());
        assertEquals(0, f.calls.get()); assertEquals(0, streaming.d.reserved().compareTo(BigDecimal.ZERO));
    }

    @Test
    void revokedReadPermissionBlocksActionAndRawModelReplayButStillAllowsStopping() throws Exception {
        streaming.block = new CountDownLatch(1);
        var target = saved("10"); var snapshot = preview(target, List.of());
        var accepted = submit(target, List.of(), snapshot.contentDigest(), "revoke-read");
        streaming.d.start();
        DurableModelWorkerIntegrationTest.await(() -> !f.executions.find(f.scope, accepted.execution().executionId()).orElseThrow().partialText().isEmpty());
        var observation = service.observe(f.http, TENANT, WORKSPACE, accepted.action().actionExecutionId());
        var rawViewer = viewer(snapshot, false);
        f.jdbc.update("DELETE FROM arte_security_resource_grant WHERE resource_id='10' AND action_code=?", CommonResourceAction.AI_PROCESS.code());
        assertThrows(BaseException.class, () -> service.events(observation, -1, 64));
        assertThrows(BaseException.class, () -> service.find(f.http, TENANT, WORKSPACE, accepted.action().actionExecutionId()));
        assertThrows(BaseException.class, () -> f.coordinator.find(rawViewer, accepted.execution().executionId()));
        assertThrows(BaseException.class, () -> f.coordinator.events(rawViewer, accepted.execution().executionId(), -1, 64));
        assertNotNull(service.cancel(f.http, TENANT, WORKSPACE, accepted.action().actionExecutionId()));
        DurableModelWorkerIntegrationTest.await(() -> !Set.of(ExecutionStatus.ACCEPTED, ExecutionStatus.RUNNING).contains(f.executions.find(f.scope, accepted.execution().executionId()).orElseThrow().status()));
        assertEquals(ExecutionStatus.OUTCOME_UNKNOWN, f.executions.find(f.scope, accepted.execution().executionId()).orElseThrow().status());
    }

    @Test
    void acceptedResponseLossAndChangedArticleRecoverTheOriginalFixedSnapshot() throws Exception {
        var target = saved("10"); var snapshot = preview(target, List.of());
        var failingStore = new AiActionStore() {
            public com.arte.ai.model.action.AiActionExecution claim(com.arte.ai.model.action.AiActionExecution draft) { return store.claim(draft); }
            public Optional<com.arte.ai.model.action.AiActionExecution> findIdempotent(ExecutionScope scope, String key) { return store.findIdempotent(scope, key); }
            public Optional<com.arte.ai.model.action.AiActionExecution> find(ExecutionScope scope, String id) { throw new IllegalStateException("response lost"); }
        };
        service = calls(new AiActionService(failingStore, f.coordinator, f.definitions.capabilityRef(), f.definitions.bindingRef(), f.clock, 8192, 10, 8192, 256, true, contexts));
        assertThrows(IllegalStateException.class, () -> submit(target, List.of(), snapshot.contentDigest(), "lost"));
        f.jdbc.update("UPDATE arte_rt_article SET row_version=2,content_text='后来修改的内容' WHERE id=10");
        wire(contexts);
        var recovered = submit(target, List.of(), snapshot.contentDigest(), "lost");
        streaming.d.start(); finished(recovered.action().actionExecutionId());
        assertTrue(f.requests.getFirst().contains(ORIGINAL)); assertFalse(f.requests.getFirst().contains("后来修改的内容"));
        assertEquals(1, count("arte_ai_new_execution")); assertEquals(1, f.calls.get());
        assertThrows(BaseException.class, () -> service.submit(f.http, TENANT, WORKSPACE, "rewrite", null, "不同要求", target, List.of(), null, snapshot.contentDigest(), "lost", true));
    }

    @Test
    void regenerationUsesOriginalSourceVersionsAndPersistsItsBudget() throws Exception {
        var target = saved("10"); var snapshot = preview(target, List.of(saved("11")));
        var first = submit(target, List.of(saved("11")), snapshot.contentDigest(), "one");
        streaming.d.start(); finished(first.action().actionExecutionId());
        f.jdbc.update("UPDATE arte_rt_article SET row_version=2,content_text='新版本未选择' WHERE id=10");
        var next = service.regenerate(f.http, TENANT, WORKSPACE, first.action().actionExecutionId(), new ModelOptions(0.3, 8), "regen", true);
        var done = finished(next.action().actionExecutionId());
        assertEquals("1", done.action().resourceContext().fragments().getFirst().source().resource().version());
        assertEquals(8, done.action().resourceContext().budget().outputTokenReserve());
        assertFalse(f.requests.getLast().contains("新版本未选择")); assertFalse(f.requests.getLast().contains("答复-1"));
        assertEquals(done.execution().executionId(), service.regenerate(f.http, TENANT, WORKSPACE, first.action().actionExecutionId(), new ModelOptions(0.3, 8), "regen", true).execution().executionId());
        assertEquals(2, f.calls.get());
    }

    @Test
    void expiredQueuedContextCannotBeSentButPreviouslyAcceptedResultsRemainQueryable() throws Exception {
        wire(new ResourceContextService(List.of(adapter), f.clock, Duration.ofSeconds(1), 8192, 8192, 256));
        var target = saved("10"); var snapshot = preview(target, List.of());
        var accepted = submit(target, List.of(), snapshot.contentDigest(), "expiry");
        f.clock.now = f.clock.now.plusSeconds(2);
        streaming.d.start();
        var done = finished(accepted.action().actionExecutionId());
        assertEquals(ExecutionStatus.TIMED_OUT, done.execution().status()); assertEquals(0, f.calls.get());
        assertEquals(done.execution().executionId(), submit(target, List.of(), snapshot.contentDigest(), "expiry").execution().executionId());
    }

    @Test
    void httpMetadataPreviewSubmissionAndSseUseActualSourceScope() throws Exception {
        try (var streams = new AiActionEventStreams(service, 2, Duration.ofSeconds(10), f.executions)) {
            var web = MockMvcBuilders.standaloneSetup(new NewAiActionController(service, streams), metadata).build();
            web.perform(get("/api/ai-new/context/articles/10").param("tenantId", TENANT).param("workspaceId", WORKSPACE))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.representation").value("stored-plain-text"));
            var target = saved("10"); var refs = List.of(saved("11"));
            var preview = web.perform(post("/api/ai-new/actions/rewrite/context").contentType("application/json").content(body(target, refs, null)))
                    .andExpect(status().isOk()).andReturn().getResponse();
            String digest = JsonParser.parseString(preview.getContentAsString()).getAsJsonObject().get("contentDigest").getAsString();
            var response = web.perform(post("/api/ai-new/actions/rewrite/executions").header("Idempotency-Key", "http")
                    .contentType("application/json").content(body(target, refs, digest))).andExpect(status().isAccepted()).andReturn().getResponse();
            String id = JsonParser.parseString(response.getContentAsString()).getAsJsonObject().getAsJsonObject("action").get("actionExecutionId").getAsString();
            streaming.d.start(); finished(id);
            var async = web.perform(get("/api/ai-new/actions/executions/" + id + "/events").param("tenantId", TENANT).param("workspaceId", WORKSPACE))
                    .andExpect(request().asyncStarted()).andReturn();
            async.getAsyncResult(3000);
            var output = web.perform(asyncDispatch(async)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
            assertTrue(output.contains("SUCCEEDED")); assertEquals(1, f.calls.get());
        }
    }

    @Test
    void metadataTamperingInExecutionBlocksBothRawAndActionReads() {
        var target = saved("10"); var snapshot = preview(target, List.of());
        var accepted = submit(target, List.of(), snapshot.contentDigest(), "tamper");
        var json = JsonParser.parseString(f.jdbc.queryForObject("SELECT resource_context_json FROM arte_ai_new_execution", String.class)).getAsJsonObject();
        json.getAsJsonArray("fragments").get(0).getAsJsonObject().addProperty("coverage", "pretend full article");
        f.jdbc.update("UPDATE arte_ai_new_execution SET resource_context_json=?", json.toString());
        assertThrows(BaseException.class, () -> service.find(f.http, TENANT, WORKSPACE, accepted.action().actionExecutionId()));
        assertThrows(BaseException.class, () -> f.coordinator.find(viewer(snapshot, false), accepted.execution().executionId()));
        assertEquals(0, f.calls.get());
    }

    @Test
    void validButDifferentLedgerContextCannotReplaceTheQueuedOrActionContext() throws Exception {
        var target = saved("10"); var reference = saved("11");
        var snapshot = preview(target, List.of(reference));
        var accepted = submit(target, List.of(reference), snapshot.contentDigest(), "associated");
        var alternative = preview(target, List.of());
        var other = submit(target, List.of(), alternative.contentDigest(), "other");
        var replacement = f.jdbc.queryForObject("SELECT resource_context_json FROM arte_ai_new_execution WHERE execution_id=?", String.class, other.execution().executionId());
        f.jdbc.update("UPDATE arte_ai_new_execution SET resource_context_json=? WHERE execution_id=?", replacement, accepted.execution().executionId());
        assertEquals(CommonErrorCode.VERSION_CONFLICT.code(), assertThrows(BaseException.class,
                () -> service.find(f.http, TENANT, WORKSPACE, accepted.action().actionExecutionId())).error().code());
        streaming.d.start();
        DurableModelWorkerIntegrationTest.await(() -> f.executions.find(f.scope, accepted.execution().executionId()).orElseThrow().status() == ExecutionStatus.FAILED);
        assertEquals(ExecutionStatus.SUCCEEDED, finished(other.action().actionExecutionId()).execution().status());
        assertEquals(1, f.calls.get());
        assertFalse(f.requests.getFirst().contains(REFERENCE));
    }

    @Test
    void coordinatorWithoutResourceVerifierCannotSendAResourceRequest() {
        var snapshot = preview(saved("10"), List.of());
        wire(null);
        var request = new com.arte.ai.model.execution.InvocationRequest<>(f.definitions.capabilityRef(), f.definitions.bindingRef(),
                new com.arte.ai.model.generation.GenerationRequest(snapshot.messages(), new ModelOptions(null, 10), List.of(), null, snapshot),
                new com.arte.ai.model.execution.ExecutionOptions(Duration.ofSeconds(90), true), viewer(snapshot, true));
        assertEquals(CommonErrorCode.UNSUPPORTED.code(), assertThrows(BaseException.class, () -> f.coordinator.prepare(request)).error().code());
        assertEquals(0, f.calls.get());
    }

    @Test
    void concurrentSameKeyPersistsAndDispatchesTheClaimedSnapshotOnly() throws Exception {
        var target = saved("10"); var references = List.of(saved("11"));
        var snapshot = preview(target, references); var viewer = viewer(snapshot, true);
        var barrier = new java.util.concurrent.CyclicBarrier(2);
        var racing = new AiActionStore() {
            public com.arte.ai.model.action.AiActionExecution claim(com.arte.ai.model.action.AiActionExecution draft) {
                try { barrier.await(3, java.util.concurrent.TimeUnit.SECONDS); }
                catch (Exception failure) { throw new RuntimeException(failure); }
                return store.claim(draft);
            }
            public Optional<com.arte.ai.model.action.AiActionExecution> find(ExecutionScope scope, String id) { return store.find(scope, id); }
            public Optional<com.arte.ai.model.action.AiActionExecution> findIdempotent(ExecutionScope scope, String key) { return store.findIdempotent(scope, key); }
        };
        var actions = new AiActionService(racing, f.coordinator, f.definitions.capabilityRef(), f.definitions.bindingRef(), f.clock, 8192, 10, 8192, 256, true, contexts);
        java.util.concurrent.Callable<AiActionResult> submit = () -> {
            f.login("alice", 1);
            try { return actions.submit(viewer, "rewrite", null, "简洁，保留事实", target, references,
                    null, snapshot.contentDigest(), "race", prepared -> f.consents.confirm(f.http, prepared)); }
            finally { org.springframework.security.core.context.SecurityContextHolder.clearContext(); }
        };
        try (var pool = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            var first = pool.submit(submit); var second = pool.submit(submit);
            var one = first.get(5, java.util.concurrent.TimeUnit.SECONDS); var two = second.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(one.action(), two.action()); assertEquals(one.execution().executionId(), two.execution().executionId());
            assertEquals(one.action().resourceContext(), one.execution().resourceContext());
            assertEquals(two.action().resourceContext(), two.execution().resourceContext());
            assertEquals(1, count("arte_ai_new_action")); assertEquals(1, count("arte_ai_new_execution"));
            streaming.d.start();
            assertEquals(ExecutionStatus.SUCCEEDED, finished(one.action().actionExecutionId()).execution().status());
            assertEquals(1, f.calls.get());
        }
    }

    private int count(String table) { return f.jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class); }
    private String body(ResourceContextSelection target, List<ResourceContextSelection> refs, String digest) {
        var o = new JsonObject(); o.addProperty("tenantId", TENANT); o.addProperty("workspaceId", WORKSPACE); o.addProperty("requirements", "简洁，保留事实");
        o.add("target", new Gson().toJsonTree(target)); o.add("references", new Gson().toJsonTree(refs));
        o.addProperty("expectedContextDigest", digest); o.addProperty("externalTransferConfirmed", true);
        return o.toString();
    }
}
