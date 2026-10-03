package com.arte.app.ainew;

import com.arte.ai.api.conversation.ChatService;
import com.arte.ai.api.conversation.ConversationService;
import com.arte.ai.conversation.ChatValues;
import com.arte.ai.model.conversation.ChatTurnResult;
import com.arte.ai.model.conversation.Conversation;
import com.arte.ai.model.generation.ModelOptions;
import com.arte.ai.spi.security.ModelConsentProvider;
import com.arte.app.security.bridge.EgressConsentService;
import com.arte.app.security.bridge.ExecutionContextFactory;
import com.arte.base.model.error.CommonErrorCode;
import com.arte.base.model.execution.CancellationStatus;
import com.arte.base.model.execution.ExecutionContext;
import com.arte.base.model.security.CommonResourceAction;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;

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
        var viewer = viewer(http, tenant, workspace, false);
        conversations.find(viewer, id);
        chats.reconcileActive(viewer, id);
        return conversations.rename(viewer, id, version, title);
    }

    public Conversation delete(HttpServletRequest http, String tenant, String workspace, String id, long version) {
        var viewer = viewer(http, tenant, workspace, false);
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
        return chats.submit(viewer(http, tenant, workspace, true), id, version, text, options, key, confirmation(http));
    }

    public ChatTurnResult regenerate(HttpServletRequest http, String tenant, String workspace, String id, long version, String original,
                                     ModelOptions options, String key, boolean confirmed) {
        requireConfirmation(confirmed);
        return chats.regenerate(viewer(http, tenant, workspace, true), id, version, original, options, key, confirmation(http));
    }

    public ChatTurnResult turn(HttpServletRequest http, String tenant, String workspace, String id, String turn) {
        return chats.find(viewer(http, tenant, workspace, false), id, turn);
    }

    public List<ChatTurnResult> history(HttpServletRequest http, String tenant, String workspace, String id, long before, int limit) {
        return chats.history(viewer(http, tenant, workspace, false), id, before, limit);
    }

    public CancellationStatus cancel(HttpServletRequest http, String tenant, String workspace, String id, String turn) {
        return chats.cancel(viewer(http, tenant, workspace, false), id, turn);
    }

    public record Observation(ExecutionContext viewer, String conversationId, String turnId, String executionId) {
    }

    public Observation observe(HttpServletRequest http, String tenant, String workspace, String id, String turn) {
        var viewer = viewer(http, tenant, workspace, false);
        var result = chats.find(viewer, id, turn);
        if (result.execution() == null) throw ChatValues.failure(CommonErrorCode.BUSY, "chat-stream");
        return new Observation(viewer, id, turn, result.execution().executionId());
    }

    public List<com.arte.base.model.execution.ExecutionEvent<com.arte.ai.model.execution.ModelEvent>> events(Observation observation, long after, int limit) {
        return chats.events(observation.viewer(), observation.conversationId(), observation.turnId(), after, limit);
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
