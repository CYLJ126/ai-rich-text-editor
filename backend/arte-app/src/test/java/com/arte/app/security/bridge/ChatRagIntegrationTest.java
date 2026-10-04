package com.arte.app.security.bridge;

import com.arte.ai.api.context.ContextService;
import com.arte.ai.api.context.RagContextService;
import com.arte.ai.api.context.ResourceContextService;
import com.arte.ai.api.conversation.ChatService;
import com.arte.ai.context.ConservativeTokenEstimator;
import com.arte.ai.context.ResourceContextValues;
import com.arte.ai.model.context.ResourceRetrievalRequest;
import com.arte.ai.model.conversation.ChatTurnResult;
import com.arte.ai.model.conversation.Conversation;
import com.arte.ai.model.execution.ExecutionStatus;
import com.arte.app.ainew.ArticleResourceRetrievalAdapter;
import com.arte.app.ainew.JdbcRetrievalPreviewStore;
import com.arte.app.ainew.NewChatCallService;
import com.arte.app.ainew.NewChatController;
import com.arte.app.api.richtext.ArticleService;
import com.arte.app.pojo.richtext.ArticleDocument;
import com.arte.app.pojo.richtext.ChunkDocument;
import com.arte.app.pojo.richtext.param.ArticleParam;
import com.arte.app.service.richtext.ArticleRetrievalQueryService;
import com.arte.app.testsupport.MySqlTestScripts;
import com.arte.base.exception.BaseException;
import com.arte.base.model.execution.CancellationStatus;
import com.arte.base.model.security.CommonResourceAction;
import com.arte.base.spi.observability.Telemetry;
import com.arte.core.es.EsSearchResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.arte.ai.model.context.ResourceRetrievalRequest.Mode.*;
import static com.arte.app.security.bridge.SecurityBridgeFixture.TENANT;
import static com.arte.app.security.bridge.SecurityBridgeFixture.WORKSPACE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 真正的权限桥、聊天账本、资料快照、耐久队列和流式模型协议；ES 仅替换网络返回。 */
class ChatRagIntegrationTest {
    ResourceContextIntegrationTest resources;
    NewChatIntegrationTest f;
    ArticleService oldSearch;
    JdbcRetrievalPreviewStore previews;
    Conversation conversation;

    @BeforeEach void setup() throws Exception {
        resources = new ResourceContextIntegrationTest(); resources.setup(); f = resources.f;
        resources.wire(new ResourceContextService(List.of(resources.adapter), f.clock, Duration.ofMinutes(10), 16384, 8192, 256));
        ScriptUtils.executeSqlScript(f.schemaConnection, MySqlTestScripts.h2Resource(Files.readString(Path.of("scripts/arte-ai-new-rag-ddl-mysql.sql"))));
        oldSearch = mock(ArticleService.class);
        when(oldSearch.hybridSearch(any())).thenReturn(hits(chunk(10, "a", "文章相关片段", 1), chunk(11, "b", "参考片段", 1)));
        var query = new ArticleRetrievalQueryService(f.jdbc, oldSearch, resources.articles);
        var provider = new ArticleResourceRetrievalAdapter(query, f.authorization, f.clock);
        var rag = new RagContextService(provider, resources.contexts, 8192);
        previews = new JdbcRetrievalPreviewStore(f.jdbc);
        var contexts = new ContextService(f.chatStore, f.coordinator, f.clock, 8192, 32, Duration.ofMinutes(10), 8192, 256,
                new ConservativeTokenEstimator()).withResources(resources.contexts);
        var chats = new ChatService(f.conversations, contexts, f.chatStore, f.coordinator, f.definitions.capabilityRef(), f.clock, 10, Telemetry.disabled(), true);
        f.service = new NewChatCallService(f.conversations, chats, f.contexts, f.consents, f.definitions, "new-ai", f.manager)
                .withRetrieval(query, provider, rag, previews, resources.contexts, 10);
        conversation = f.create();
    }
    @AfterEach void cleanup() throws Exception { resources.cleanup(); }

    ResourceRetrievalRequest selection(ResourceRetrievalRequest.Mode mode, String... ids) {
        return new ResourceRetrievalRequest(mode, List.of(ids), true, 10);
    }
    JdbcRetrievalPreviewStore.Preview preview(ResourceRetrievalRequest selection) {
        return f.service.preview(f.http, TENANT, WORKSPACE, conversation.conversationId(), conversation.version(), "解释文章", selection, null);
    }
    ChatTurnResult submit(JdbcRetrievalPreviewStore.Preview preview, String key) {
        return f.service.submit(f.http, TENANT, WORKSPACE, conversation.conversationId(), conversation.version(), "解释文章", null, key, true,
                preview.previewId(), preview.context().contentDigest());
    }
    static ChunkDocument chunk(int id, String chunk, String text, int version) {
        return ChunkDocument.builder().articleId(id).chunkId(chunk).content(text).contentWithBreadcrumb(text)
                .articleMeta(ArticleDocument.builder().articleId(id).rowVersion(version).build()).build();
    }
    static EsSearchResponse<ChunkDocument> hits(ChunkDocument... chunks) {
        return new EsSearchResponse<>(Arrays.stream(chunks).map(value -> new EsSearchResponse.Hit<>(value.getChunkId(), 1f, value, Map.<String, List<String>>of())).toList(), chunks.length, 0, chunks.length, false);
    }
    void failStage(String stage, Runnable work) { assertEquals(stage, assertThrows(BaseException.class, work::run).error().failureStage()); }

    @Test void fullTextPreviewDoesNotCallEsOrModelAndUsesSavedProjection() {
        var preview = preview(selection(ARTICLE_FULL_TEXT, "10"));
        assertEquals(ResourceContextIntegrationTest.ORIGINAL, preview.context().fragments().getFirst().content());
        assertFalse(preview.context().fragments().getFirst().truncated());
        assertNull(preview.context().fragments().getFirst().source().resource().rangeRef());
        assertEquals(ResourceContextValues.textDigest(ResourceContextIntegrationTest.ORIGINAL), preview.context().fragments().getFirst().source().resource().contentDigest());
        assertEquals(8192, preview.context().budget().inputByteLimit());
        assertEquals(0, f.calls.get()); verifyNoInteractions(oldSearch);
        assertEquals(preview, previews.find(f.scope, preview.previewId()));
    }
    @Test void missingOrDisabledApplicationReadPolicyHasAnExplicitHttpFailureBeforeSearching() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new NewChatController(f.service)).build();
        var body = "{\"tenantId\":\"" + TENANT + "\",\"workspaceId\":\"" + WORKSPACE + "\",\"expectedVersion\":1,\"text\":\"解释文章\",\"retrieval\":{\"mode\":\"ARTICLE_LIBRARY\",\"articleIds\":[],\"semanticSearch\":true,\"maxResults\":10}}";
        f.jdbc.update("DELETE FROM arte_security_application_policy WHERE action_code=?", CommonResourceAction.READ.code());
        var response = mvc.perform(post("/api/ai-new/conversations/" + conversation.conversationId() + "/rag-preview")
                .contentType("application/json").content(body)).andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        var error = com.google.gson.JsonParser.parseString(response).getAsJsonObject();
        assertEquals("application-policy-read", error.get("failureStage").getAsString());
        assertEquals("arte.common.unauthorized", error.get("code").getAsString());
        assertEquals("NONE", error.get("sideEffectStatus").getAsString());
        f.policy(CommonResourceAction.READ.code());
        f.jdbc.update("UPDATE arte_security_application_policy SET binding_enabled=FALSE WHERE action_code=?", CommonResourceAction.READ.code());
        var disabled = assertThrows(ExecutionAccessDeniedException.class, () -> preview(selection(ARTICLE_LIBRARY)));
        assertEquals("application-policy-read", disabled.failureStage());
        assertNotNull(f.service.find(f.http, TENANT, WORKSPACE, conversation.conversationId()));
        verifyNoInteractions(oldSearch); assertEquals(0, f.calls.get());
    }
    @Test void selectedEsRetrievalFreezesChunksAndStreamsThemThroughTheSharedWorker() throws Exception {
        var preview = preview(selection(SELECTED_ARTICLES, "10", "11"));
        var params = ArgumentCaptor.forClass(ArticleParam.class); verify(oldSearch).hybridSearch(params.capture());
        assertEquals(Set.of(10, 11), params.getValue().getArticleIds());
        assertEquals("解释文章", params.getValue().getSearchBingoText());
        assertTrue(params.getValue().getSemanticSearch()); assertEquals(0, params.getValue().getEsCurrent());
        assertEquals("es-chunk:a", preview.context().fragments().getFirst().source().resource().rangeRef());
        var accepted = submit(preview, "rag-one");
        var snapshot = f.chatStore.snapshot(f.scope, accepted.turn().contextSnapshotId()).orElseThrow();
        assertEquals(snapshot.resourceContext(), accepted.execution().resourceContext());
        assertEquals(2, snapshot.fragments().size());
        resources.streaming.d.start(); var done = f.finished(conversation, accepted.turn().turnId());
        assertEquals(ExecutionStatus.SUCCEEDED, done.execution().status());
        assertTrue(f.requests.getFirst().contains("文章相关片段")); assertTrue(f.requests.getFirst().contains("参考片段"));
        assertTrue(f.requests.getFirst().contains("解释文章"));
        assertFalse(f.requests.getFirst().contains(ResourceContextIntegrationTest.ORIGINAL));
        var events = f.service.events(f.service.observe(f.http, TENANT, WORKSPACE, conversation.conversationId(), done.turn().turnId()), -1, 64);
        assertTrue(events.stream().anyMatch(event -> event.payload().textDelta() != null));
        assertEquals(1, f.calls.get());
    }
    @Test void replayAfterPreviewExpiryAndArticleEditKeepsAcceptedInputWithoutSearchingAgain() throws Exception {
        var preview = preview(selection(ARTICLE_FULL_TEXT, "10")); var accepted = submit(preview, "replay");
        resources.streaming.d.start(); f.finished(conversation, accepted.turn().turnId());
        f.jdbc.update("UPDATE arte_rt_article SET row_version=2,content_text='替换正文' WHERE id=10");
        f.clock.now = f.clock.now.plus(Duration.ofMinutes(11));
        var replay = submit(preview, "replay");
        assertEquals(accepted.execution().executionId(), replay.execution().executionId());
        assertEquals(1, f.calls.get()); verifyNoInteractions(oldSearch);
        assertTrue(replay.execution().resourceContext().messages().toString().contains(ResourceContextIntegrationTest.ORIGINAL));
    }
    @Test void regenerationKeepsOriginalSourcesAndContentRatherThanReretrieving() throws Exception {
        var preview = preview(selection(SELECTED_ARTICLES, "10", "11"));
        var accepted = submit(preview, "first"); resources.streaming.d.start(); f.finished(conversation, accepted.turn().turnId());
        f.jdbc.update("UPDATE arte_rt_article SET row_version=2,content_text='新正文' WHERE id=10");
        when(oldSearch.hybridSearch(any())).thenReturn(hits(chunk(10, "new", "新片段", 2)));
        var current = f.current(conversation);
        var regen = f.service.regenerate(f.http, TENANT, WORKSPACE, conversation.conversationId(), current.version(), accepted.turn().turnId(), null, "regen", true);
        f.finished(conversation, regen.turn().turnId());
        assertTrue(f.requests.getLast().contains("文章相关片段")); assertFalse(f.requests.getLast().contains("新片段"));
        assertEquals(accepted.execution().resourceContext().fragments(), regen.execution().resourceContext().fragments());
        verify(oldSearch, times(1)).hybridSearch(any());
    }
    @Test void plainFollowupKeepsHistoricalAnswerProvenanceAndRequiresEgressForIt() throws Exception {
        var accepted = submit(preview(selection(ARTICLE_FULL_TEXT, "10")), "first");
        resources.streaming.d.start(); f.finished(conversation, accepted.turn().turnId());
        var next = f.submit(f.current(conversation), "继续解释", "next"); f.finished(conversation, next.turn().turnId());
        assertEquals(1, next.execution().resourceContext().fragments().size());
        assertTrue(next.execution().resourceContext().fragments().getFirst().coverageDescription().contains("派生回答"));
        assertTrue(f.requests.getLast().contains("答复-1")); assertFalse(f.requests.getLast().contains(ResourceContextIntegrationTest.ORIGINAL));
        f.jdbc.update("UPDATE arte_security_resource_grant SET enabled=FALSE WHERE resource_id='10' AND action_code=?", CommonResourceAction.EGRESS.code());
        assertThrows(BaseException.class, () -> f.submit(f.current(conversation), "再次解释", "denied"));
        assertEquals(2, f.calls.get());
    }
    @Test void emptyAuthorizedLibraryNeverCallsUnrestrictedEs() {
        f.jdbc.update("UPDATE arte_security_resource_grant SET enabled=FALSE WHERE action_code=?", CommonResourceAction.AI_PROCESS.code());
        failStage("rag-no-results", () -> preview(selection(ARTICLE_LIBRARY)));
        verifyNoInteractions(oldSearch); assertEquals(0, f.calls.get());
    }
    @Test void libraryFiltersOutDeniedAndOtherWorkspaceArticlesBeforeSearch() {
        f.jdbc.update("UPDATE arte_security_resource SET workspace_id='other' WHERE resource_id='11' AND resource_type='ARTICLE'");
        when(oldSearch.hybridSearch(any())).thenReturn(hits(chunk(10, "a", "片段", 1)));
        preview(selection(ARTICLE_LIBRARY));
        var params = ArgumentCaptor.forClass(ArticleParam.class); verify(oldSearch).hybridSearch(params.capture());
        assertEquals(Set.of(10), params.getValue().getArticleIds());
    }
    @Test void selectedDeniedArticleFailsBeforeReadingContentOrEs() {
        f.jdbc.update("UPDATE arte_security_resource_grant SET enabled=FALSE WHERE resource_id='10' AND action_code=?", CommonResourceAction.AI_PROCESS.code());
        failStage("rag-article", () -> preview(selection(ARTICLE_FULL_TEXT, "10"))); verifyNoInteractions(oldSearch);
        failStage("rag-article", () -> preview(selection(SELECTED_ARTICLES, "10", "11"))); verifyNoInteractions(oldSearch);
    }
    @Test void zeroHitsStaleIndexAndOutOfScopeHitsAreExplicitFailures() {
        when(oldSearch.hybridSearch(any())).thenReturn(EsSearchResponse.empty());
        failStage("rag-no-results", () -> preview(selection(SELECTED_ARTICLES, "10")));
        when(oldSearch.hybridSearch(any())).thenReturn(hits(chunk(10, "a", "旧片段", 0)));
        failStage("rag-index-stale", () -> preview(selection(SELECTED_ARTICLES, "10")));
        when(oldSearch.hybridSearch(any())).thenReturn(hits(chunk(11, "a", "越界片段", 1)));
        failStage("rag-search-scope", () -> preview(selection(SELECTED_ARTICLES, "10")));
        assertEquals(0, f.calls.get());
    }
    @Test void oversizedFullTextIsRejectedWithoutTruncating() {
        f.jdbc.update("UPDATE arte_rt_article SET content_text=? WHERE id=10", "长文".repeat(4096));
        failStage("resource-context-capacity", () -> preview(selection(ARTICLE_FULL_TEXT, "10")));
        assertEquals(0, f.calls.get()); verifyNoInteractions(oldSearch);
    }
    @Test void editDuringEsSearchCannotReturnPreviouslyFreshChunksAsCurrent() {
        when(oldSearch.hybridSearch(any())).thenAnswer(call -> {
            f.jdbc.update("UPDATE arte_rt_article SET row_version=2 WHERE id=10");
            return hits(chunk(10, "a", "检索期间变旧的片段", 1));
        });
        failStage("rag-index-stale", () -> preview(selection(SELECTED_ARTICLES, "10")));
        assertEquals(0, f.calls.get());
    }
    @Test void expiredUnacceptedPreviewCannotBeRenewedBySubmission() {
        var preview = preview(selection(ARTICLE_FULL_TEXT, "10")); f.clock.now = f.clock.now.plus(Duration.ofMinutes(11));
        failStage("rag-preview-expired", () -> submit(preview, "expired")); assertEquals(0, f.calls.get());
    }
    @Test void changedQuestionDigestAndForeignScopeCannotUseThePreview() {
        var preview = preview(selection(ARTICLE_FULL_TEXT, "10"));
        failStage("rag-preview", () -> f.service.submit(f.http, TENANT, WORKSPACE, conversation.conversationId(), conversation.version(), "替换问题", null,
                "changed", true, preview.previewId(), preview.context().contentDigest()));
        failStage("rag-preview", () -> f.service.submit(f.http, TENANT, WORKSPACE, conversation.conversationId(), conversation.version(), "解释文章", null,
                "changed", true, preview.previewId(), "sha256:" + "0".repeat(64)));
        assertThrows(BaseException.class, () -> previews.find(new com.arte.base.model.identity.ExecutionScope(TENANT, WORKSPACE, new com.arte.base.model.identity.PrincipalRef("2", com.arte.base.model.identity.PrincipalType.USER)), preview.previewId()));
        assertEquals(0, f.calls.get());
    }
    @Test void revokedPermissionsStopReadsAndEventsButAllowCancellation() throws Exception {
        var accepted = submit(preview(selection(ARTICLE_FULL_TEXT, "10")), "queued");
        var observation = f.service.observe(f.http, TENANT, WORKSPACE, conversation.conversationId(), accepted.turn().turnId());
        f.jdbc.update("UPDATE arte_security_resource_grant SET enabled=FALSE WHERE resource_id='10' AND action_code=?", CommonResourceAction.AI_PROCESS.code());
        assertThrows(BaseException.class, () -> f.service.turn(f.http, TENANT, WORKSPACE, conversation.conversationId(), accepted.turn().turnId()));
        assertThrows(BaseException.class, () -> f.service.events(observation, -1, 64));
        var cancelled = f.service.cancel(f.http, TENANT, WORKSPACE, conversation.conversationId(), accepted.turn().turnId());
        assertTrue(cancelled == CancellationStatus.CANCELLED || cancelled == CancellationStatus.REQUEST_ACCEPTED);
        assertEquals(0, f.calls.get());
    }
    @Test void workerRechecksEgressAfterAcceptingQueuedRag() throws Exception {
        var accepted = submit(preview(selection(ARTICLE_FULL_TEXT, "10")), "queued");
        f.jdbc.update("UPDATE arte_security_resource_grant SET enabled=FALSE WHERE resource_id='10' AND action_code=?", CommonResourceAction.EGRESS.code());
        resources.streaming.d.start();
        DurableModelWorkerIntegrationTest.await(() -> f.executions.find(f.scope, accepted.execution().executionId()).orElseThrow().status() == ExecutionStatus.FAILED);
        assertEquals(0, f.calls.get());
    }
    @Test void olderHistoryPageRegistersItsOwnSourcesAfterMoreThan256Submissions() throws Exception {
        var accepted = submit(preview(selection(ARTICLE_FULL_TEXT, "10")), "first");
        resources.streaming.d.start(); f.finished(conversation, accepted.turn().turnId());
        String scopeKey = f.jdbc.queryForObject("SELECT scope_key FROM arte_ai_new_conversation WHERE conversation_id=?", String.class, conversation.conversationId());
        for (int index = 2; index <= 258; index++) {
            f.jdbc.update("INSERT INTO arte_ai_new_turn(turn_id,conversation_id,scope_key,sequence_no,conversation_version,row_version,kind,status,payload_format,input_json,model_options_json,idempotency_operation,idempotency_key,request_digest,created_at,updated_at,slot_released_at,rejection_code,rejection_stage,rejection_retryable,rejection_side_effect_status,rejection_result_certainty)"
                            + " VALUES (?,?,?,?,1,1,'MESSAGE','REJECTED','arte.chat.turn.v1','[{\"role\":\"USER\",\"parts\":[{\"type\":\"text\",\"text\":\"padding\"}]}]','{\"maxOutputTokens\":10}','chat.turn.submit',?,?,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,'denied','test',FALSE,'NONE','CONFIRMED')",
                    "padding-" + index, conversation.conversationId(), scopeKey, index, "padding-" + index, "sha256:" + "a".repeat(64));
        }
        var page = f.service.history(f.http, TENANT, WORKSPACE, conversation.conversationId(), 2, 10);
        assertEquals(1, page.size()); assertEquals(accepted.turn().turnId(), page.getFirst().turn().turnId());
        assertEquals(1, page.getFirst().execution().resourceContext().fragments().size());
    }
    @Test void httpPreviewAndSubmissionShareTheSavedSnapshot() throws Exception {
        var mvc = MockMvcBuilders.standaloneSetup(new NewChatController(f.service)).build();
        var body = "{\"tenantId\":\"" + TENANT + "\",\"workspaceId\":\"" + WORKSPACE + "\",\"expectedVersion\":1,\"text\":\"解释文章\",\"retrieval\":{\"mode\":\"ARTICLE_FULL_TEXT\",\"articleIds\":[\"10\"],\"semanticSearch\":true,\"maxResults\":10}}";
        var response = mvc.perform(post("/api/ai-new/conversations/" + conversation.conversationId() + "/rag-preview").contentType("application/json").content(body)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var json = com.google.gson.JsonParser.parseString(response).getAsJsonObject();
        body = body.substring(0, body.indexOf(",\"retrieval\"")) + ",\"externalTransferConfirmed\":true,\"previewId\":\"" + json.get("previewId").getAsString()
                + "\",\"expectedContextDigest\":\"" + json.getAsJsonObject("context").get("contentDigest").getAsString() + "\"}";
        mvc.perform(post("/api/ai-new/conversations/" + conversation.conversationId() + "/turns").header("Idempotency-Key", "http-rag").contentType("application/json").content(body)).andExpect(status().isAccepted());
        verifyNoInteractions(oldSearch);
    }
}
