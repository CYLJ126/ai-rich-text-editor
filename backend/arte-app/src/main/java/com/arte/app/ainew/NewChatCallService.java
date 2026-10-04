package com.arte.app.ainew;

import com.arte.ai.api.context.RagContextService;
import com.arte.ai.api.context.ResourceContextService;
import com.arte.ai.api.conversation.ChatService;
import com.arte.ai.api.conversation.ConversationService;
import com.arte.ai.conversation.ChatValues;
import com.arte.ai.model.context.ContextSnapshot;
import com.arte.ai.model.context.ResourceRetrievalRequest;
import com.arte.ai.model.conversation.ChatTurnResult;
import com.arte.ai.model.conversation.Conversation;
import com.arte.ai.model.generation.ModelOptions;
import com.arte.ai.model.message.Message;
import com.arte.ai.model.message.MessageRole;
import com.arte.ai.model.message.TextPart;
import com.arte.ai.spi.security.ModelConsentProvider;
import com.arte.app.security.bridge.EgressConsentService;
import com.arte.app.security.bridge.ExecutionContextFactory;
import com.arte.app.service.richtext.ArticleRetrievalQueryService;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.CancellationStatus;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.resource.ResourceRef;
import com.arte.base.model.security.CommonResourceAction;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

/**
 * 新 HTTP 场景组合；主体由现有登录会话确定，不读取旧 AI 表。
 */
public final class NewChatCallService {
    private final ConversationService conversations;
    private final ChatService chats;
    private final ExecutionContextFactory contexts;
    private final EgressConsentService consents;
    private final ConfiguredModelDefinitions definitions;
    private final String application;
    private final TransactionTemplate consentWrites;
    private ArticleRetrievalQueryService retrievalArticles;
    private ArticleResourceRetrievalAdapter retrievalProvider;
    private RagContextService ragContexts;
    private JdbcRetrievalPreviewStore previews;
    private ResourceContextService resourceContexts;
    private int outputTokens;

    public NewChatCallService withRetrieval(ArticleRetrievalQueryService articles,
                                            ArticleResourceRetrievalAdapter provider, RagContextService rag,
                                            JdbcRetrievalPreviewStore previews, ResourceContextService contexts, int tokens) {
        this.retrievalArticles = articles; this.retrievalProvider = provider; this.ragContexts = rag;
        this.previews = previews; this.resourceContexts = contexts; this.outputTokens = tokens;
        return this;
    }

    public JdbcRetrievalPreviewStore.Preview preview(HttpServletRequest http, String tenant, String workspace, String id,
                                                     long version, String text, ResourceRetrievalRequest selection, ModelOptions options) {
        requireRetrieval();
        var initial = viewer(http, tenant, workspace, false);
        var conversation = conversations.find(initial, id);
        if (conversation.version() != version) throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "rag-preview");
        var candidates = retrievalArticles.candidates(initial.scope(), selection.articleIds()).stream().filter(value ->
                selection.mode() == ResourceRetrievalRequest.Mode.ARTICLE_LIBRARY || selection.articleIds().contains(value.id())).toList();
        var scoped = resourceViewer(http, tenant, workspace, candidates.stream().map(value -> value.resource()).toList(), false);
        var normalized = retrievalOptions(options);
        var snapshot = ragContexts.prepare(scoped, conversation.modelBindingRef(), text, selection, normalized.maxOutputTokens());
        var finalViewer = resourceViewer(http, tenant, workspace, snapshot.fragments().stream().map(f -> f.source().resource()).toList(), false);
        resourceContexts.recheck(finalViewer, snapshot, false);
        return previews.save(id, version, previewDigest(id, version, text, normalized), snapshot);
    }

    public List<ArticleRetrievalQueryService.Metadata> articles(HttpServletRequest http, String tenant, String workspace) {
        requireRetrieval();
        var initial = viewer(http, tenant, workspace, false);
        var candidates = retrievalArticles.candidates(initial.scope());
        var scoped = resourceViewer(http, tenant, workspace, candidates.stream().map(value -> value.resource()).toList(), false);
        return retrievalProvider.authorized(scoped, new ResourceRetrievalRequest(
                ResourceRetrievalRequest.Mode.ARTICLE_LIBRARY, List.of(), false, 10));
    }

    public ChatTurnResult submit(HttpServletRequest http, String tenant, String workspace, String id, long version, String text,
                                 ModelOptions options, String key, boolean confirmed, String previewId, String expectedContextDigest) {
        if (previewId == null && expectedContextDigest == null) return submit(http, tenant, workspace, id, version, text, options, key, confirmed);
        requireConfirmation(confirmed); requireRetrieval();
        var initial = viewer(http, tenant, workspace, false);
        conversations.find(initial, id);
        var preview = previews.find(initial.scope(), previewId);
        if (!preview.conversationId().equals(id) || preview.conversationVersion() != version
                || !preview.requestDigest().equals(previewDigest(id, version, text, retrievalOptions(options)))
                || !preview.context().contentDigest().equals(expectedContextDigest))
            throw ChatValues.failure(CommonErrorCode.VERSION_CONFLICT, "rag-preview");
        var refs = new ArrayList<>(chats.resourceRefs(initial, id));
        preview.context().fragments().forEach(f -> refs.add(f.source().resource()));
        return chats.submit(resourceViewer(http, tenant, workspace, refs, true), id, version, text, options, key, confirmation(http), preview.context());
    }

    public JdbcRetrievalPreviewStore.Preview preview(HttpServletRequest http, String tenant, String workspace, String id, String previewId) {
        requireRetrieval();
        var initial = viewer(http, tenant, workspace, false);
        conversations.find(initial, id);
        var preview = previews.find(initial.scope(), previewId);
        if (!preview.conversationId().equals(id)) throw ChatValues.failure(CommonErrorCode.NOT_FOUND, "rag-preview");
        resourceContexts.recheck(resourceViewer(http, tenant, workspace, preview.context().fragments().stream().map(f -> f.source().resource()).toList(), false), preview.context(), false);
        return preview;
    }
    public ContextSnapshot context(HttpServletRequest http, String tenant, String workspace, String id, String turn) {
        return chats.context(conversationViewer(http, tenant, workspace, id, turn, false), id, turn);
    }

    private ModelOptions retrievalOptions(ModelOptions options) {
        int tokens = options == null || options.maxOutputTokens() == null ? outputTokens : options.maxOutputTokens();
        if (tokens > outputTokens) throw new IllegalArgumentException("maxOutputTokens exceeds model limit");
        return new ModelOptions(options == null ? null : options.temperature(), tokens);
    }
    private static String previewDigest(String id, long version, String text, ModelOptions options) {
        return ChatValues.submission(id, version, null, List.of(new Message(
                MessageRole.USER, List.of(new TextPart(text)))), options);
    }
    private void requireRetrieval() {
        if (ragContexts == null) throw ChatValues.failure(CommonErrorCode.UNSUPPORTED, "rag-disabled");
    }

    public NewChatCallService(ConversationService conversations, ChatService chats, ExecutionContextFactory contexts,
                              EgressConsentService consents, ConfiguredModelDefinitions definitions, String application, PlatformTransactionManager manager) {
        this.conversations = conversations;
        this.chats = chats;
        this.contexts = contexts;
        this.consents = consents;
        this.definitions = definitions;
        this.application = application;
        consentWrites = new TransactionTemplate(manager);
        consentWrites.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        consentWrites.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    public Conversation create(HttpServletRequest http, String tenant, String workspace, String title) {
        return conversations.create(viewer(http, tenant, workspace, false), title, definitions.bindingRef());
    }

    public Conversation find(HttpServletRequest http, String tenant, String workspace, String id) {
        return conversations.find(viewer(http, tenant, workspace, false), id);
    }

    public List<Conversation> list(HttpServletRequest http, String tenant, String workspace, String title, int offset, int limit) {
        return conversations.list(viewer(http, tenant, workspace, false), definitions.bindingRef(), title, offset, limit);
    }

    public Conversation rename(HttpServletRequest http, String tenant, String workspace, String id, long version, String title) {
        var viewer = conversationViewer(http, tenant, workspace, id, null, false);
        conversations.find(viewer, id);
        chats.reconcileActive(viewer, id);
        return conversations.rename(viewer, id, version, title);
    }

    public Conversation delete(HttpServletRequest http, String tenant, String workspace, String id, long version) {
        var viewer = conversationViewer(http, tenant, workspace, id, null, false);
        conversations.find(viewer, id);
        chats.reconcileActive(viewer, id);
        return conversations.delete(viewer, id, version);
    }

    /**
     * 提交（保存）问题
     *
     * @param http      HTTP 请求
     * @param tenant    租户
     * @param workspace 工作区
     * @param id        会话 ID
     * @param version   版本号
     * @param text      问题文本
     * @param options   模型选项
     * @param key       密钥
     * @param confirmed 是否确认
     * @return 本轮结果
     */
    public ChatTurnResult submit(HttpServletRequest http, String tenant, String workspace, String id, long version, String text,
                                 ModelOptions options, String key, boolean confirmed) {
        requireConfirmation(confirmed);
        return chats.submit(conversationViewer(http, tenant, workspace, id, null, true), id, version, text, options, key, confirmation(http));
    }

    public ChatTurnResult regenerate(HttpServletRequest http, String tenant, String workspace, String id, long version, String original,
                                     ModelOptions options, String key, boolean confirmed) {
        requireConfirmation(confirmed);
        return chats.regenerate(conversationViewer(http, tenant, workspace, id, null, true), id, version, original, options, key, confirmation(http));
    }

    public ChatTurnResult turn(HttpServletRequest http, String tenant, String workspace, String id, String turn) {
        return chats.find(conversationViewer(http, tenant, workspace, id, turn, false), id, turn);
    }

    public List<ChatTurnResult> history(HttpServletRequest http, String tenant, String workspace, String id, long before, int limit) {
        var initial = viewer(http, tenant, workspace, false);
        var refs = chats.resourceRefs(initial, id, before, limit);
        return chats.history(refs.isEmpty() ? initial : resourceViewer(http, tenant, workspace, refs, false), id, before, limit);
    }

    public CancellationStatus cancel(HttpServletRequest http, String tenant, String workspace, String id, String turn) {
        return chats.cancel(viewer(http, tenant, workspace, false), id, turn);
    }

    public record Observation(ExecutionContext viewer, String conversationId, String turnId, String executionId) {
    }

    public Observation observe(HttpServletRequest http, String tenant, String workspace, String id, String turn) {
        var viewer = conversationViewer(http, tenant, workspace, id, turn, false);
        var result = chats.find(viewer, id, turn);
        if (result.execution() == null) throw ChatValues.failure(CommonErrorCode.BUSY, "chat-stream");
        return new Observation(viewer, id, turn, result.execution().executionId());
    }

    public List<com.arte.base.model.execution.ExecutionEvent<com.arte.ai.model.execution.ModelEvent>> events(Observation observation, long after, int limit) {
        return chats.events(observation.viewer(), observation.conversationId(), observation.turnId(), after, limit);
    }

    private ExecutionContext conversationViewer(HttpServletRequest http, String tenant, String workspace, String id, String turn, boolean external) {
        var initial = viewer(http, tenant, workspace, false);
        var refs = turn == null ? chats.resourceRefs(initial, id) : chats.resourceRefs(initial, id, turn);
        return refs.isEmpty() ? (external ? viewer(http, tenant, workspace, true) : initial) : resourceViewer(http, tenant, workspace, refs, external);
    }

    private ExecutionContext resourceViewer(HttpServletRequest http, String tenant, String workspace,
                                             List<ResourceRef> refs, boolean external) {
        var actions = new HashSet<String>(Set.of(CommonResourceAction.AI_PROCESS.code()));
        if (external) actions.add(CommonResourceAction.EGRESS.code());
        var requested = new HashMap<ResourceRef, Set<String>>();
        for (var ref : refs) {
            var source = new HashSet<String>(Set.of(CommonResourceAction.READ.code(), CommonResourceAction.AI_PROCESS.code()));
            if (external) source.add(CommonResourceAction.EGRESS.code());
            actions.addAll(source); requested.put(ref, Set.copyOf(source));
        }
        return contexts.create(http, tenant, workspace, application, definitions.bindingRef().definitionId(), Set.copyOf(actions), requested);
    }

    private ExecutionContext viewer(HttpServletRequest http, String tenant, String workspace, boolean external) {
        Set<String> actions = external ? Set.of(CommonResourceAction.AI_PROCESS.code(), CommonResourceAction.EGRESS.code()) : Set.of(CommonResourceAction.AI_PROCESS.code());
        return contexts.create(http, tenant, workspace, application, definitions.bindingRef().definitionId(), actions);
    }

    private ModelConsentProvider confirmation(HttpServletRequest http) {
        return prepared -> {
            try {
                return consentWrites.execute(tx -> consents.confirm(http, prepared));
            } catch (AccessDeniedException denied) {
                throw ChatValues.failure(CommonErrorCode.UNAUTHORIZED, "chat-consent");
            }
        };
    }

    private static void requireConfirmation(boolean confirmed) {
        if (!confirmed) throw new AccessDeniedException("arte.ai.external_transfer_confirmation_required");
    }
}
