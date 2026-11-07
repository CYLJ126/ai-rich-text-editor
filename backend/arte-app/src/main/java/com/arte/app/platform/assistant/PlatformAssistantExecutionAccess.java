package com.arte.app.platform.assistant;

import com.arte.ainew.application.assistant.DefaultAssistantManager;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.common.execution.ExecutionPrincipal;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence;
import com.arte.ainew.pojo.assistant.Assistants.*;
import com.arte.ainew.pojo.context.AssistantSource;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.spi.assistant.AssistantContextAccess;
import com.arte.ainew.spi.assistant.AssistantTrialVerifier;
import com.arte.app.platform.persistence.JdbcAssistantStore;
import com.arte.app.platform.persistence.PlatformPersistenceExecutor;
import com.arte.core.api.access.AuthorizationService;
import com.arte.core.application.workspace.WorkspaceGovernanceLocks;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.exception.platform.PlatformException;
import com.arte.core.pojo.access.AccessRequest;
import com.arte.core.pojo.access.Action;
import com.arte.core.pojo.access.ResourceRef;
import com.arte.core.pojo.platform.ExecutionOwner;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.pojo.platform.TrustedActor;
import com.arte.core.pojo.reference.DefinitionRef;
import com.arte.core.spi.platform.IdentityProvider;
import com.arte.core.spi.platform.PublicTransactionRunner;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;
import java.util.Objects;

import static com.arte.core.constant.platform.PlatformActions.*;
import static com.arte.core.constant.platform.PlatformResourceTypes.ASSISTANT;

/**
 * 助手执行权限的平台适配器
 * <p>
 * 负责解析发布配置、复核试用和会话的执行资格，以及校验发布所需的试用结果。
 * <p>
 * 执行资格复核与助手管理写入共用工作空间治理锁，在事务内检查当前身份、资源状态和权限。
 * 会话历史摘要单独读取，并返回当前使用资格和停用状态。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 00:45 ✾
 */
public final class PlatformAssistantExecutionAccess implements AssistantContextAccess, AssistantTrialVerifier {

    private final JdbcAssistantStore store;
    private final PublicTransactionRunner transactions;
    private final WorkspaceGovernanceLocks locks;
    private final AuthorizationService authorization;
    private final IdentityProvider identities;
    private final PlatformPersistenceExecutor executor;
    private final ObjectProvider<MybatisPayloadPersistence> payloads;
    private final ObjectProvider<MybatisExecutionPersistence> executions;
    private final Clock clock;

    public PlatformAssistantExecutionAccess(JdbcAssistantStore store, PublicTransactionRunner transactions,
                                            WorkspaceGovernanceLocks locks, AuthorizationService authorization, IdentityProvider identities,
                                            PlatformPersistenceExecutor executor, ObjectProvider<MybatisPayloadPersistence> payloads,
                                            ObjectProvider<MybatisExecutionPersistence> executions, Clock clock) {
        this.store = store;
        this.transactions = transactions;
        this.locks = locks;
        this.authorization = authorization;
        this.identities = identities;
        this.executor = executor;
        this.payloads = payloads;
        this.executions = executions;
        this.clock = clock;
    }

    @Override
    public Mono<Prompt> resolve(ExecutionContext context, DefinitionRef profile) {
        return executor.inTransaction(() -> published(context, profile, false));
    }

    @Override
    public void requireNewConversation(ExecutionContext context, DefinitionRef profile) {
        published(context, profile, true);
    }

    /**
     * 在当前写事务中锁定工作空间授权范围，校验助手使用权限并读取指定发布版本的指令。
     *
     * @param context         包含当前主体、租户和工作空间的可信执行上下文。
     * @param profile         类型为 {@code chat-profile} 的精确发布版本引用。
     * @param newConversation 为 {@code true} 时必须引用当前发布版本，为 {@code false} 时可解析已有会话绑定的历史版本。
     * @return 该发布版本的助手来源和供系统消息使用的指令。
     */
    private Prompt published(ExecutionContext context, DefinitionRef profile, boolean newConversation) {
        Scope scope = scope(context);
        TrustedActor actor = lockActor(context);
        Resource resource = store.findByProfile(scope, profile.id(), true).orElseThrow(PlatformAssistantExecutionAccess::unavailable);
        require(actor, resource, ASSISTANT_USE);
        long version = DefaultAssistantManager.parseVersion(profile.version());
        if (resource.state() != State.ACTIVE || newConversation && !Objects.equals(version, resource.releaseVersion())) {
            throw unavailable();
        }
        Release release = store.release(resource.id(), version).orElseThrow(PlatformAssistantExecutionAccess::unavailable);
        return new Prompt(new AssistantSource(resource.id(), profile, null, release.draftRevision(),
                release.contentDigest(), release.content().displayName(), null), instruction(release.content()));
    }

    @Override
    public void requireCurrent(ExecutionContext context, ContextSnapshot snapshot) {
        AssistantSource source = snapshot.assistant();
        if (source.profile() != null) {
            Prompt prompt = published(context, source.profile(), false);
            if (!source.equals(prompt.source()) || !hasInstruction(snapshot, prompt.instruction())) {
                throw unavailable();
            }
            return;
        }
        requireTrialAccess(context, source.assistantId());
        Trial trial = store.trial(source.assistantId(), source.trialId()).orElseThrow(PlatformAssistantExecutionAccess::unavailable);
        ContextSnapshot saved = payloadStore().findInCurrentTransaction(ExecutionOwners.from(context), trial.snapshotId());
        if (!matches(trial, saved) || !snapshot.equals(saved) || !trial.invocationId().equals(context.executionId())
                || !trial.actorId().equals(context.authorization().principal().subjectId())) {
            throw unavailable();
        }
    }

    @Override
    public Mono<Void> requireEligible(ExecutionContext context, ContextSnapshot snapshot) {
        return executor.inTransaction(() -> {
            requireCurrent(context, snapshot);
            return Boolean.TRUE;
        }).then();
    }

    @Override
    public void requireSucceeded(Scope scope, Trial trial) {
        transactions.requireWritable();
        ExecutionOwner owner = new ExecutionOwner(scope.tenantId(), scope.workspaceId(), trial.actorId());
        MybatisExecutionPersistence store = executions.getIfAvailable();
        if (store == null) {
            throw new PlatformException(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED, "assistant-trial");
        }
        Invocation invocation = store.findInCurrentTransaction(owner, trial.invocationId());
        ContextSnapshot snapshot = payloadStore().findInCurrentTransaction(owner, trial.snapshotId());
        if (invocation == null || invocation.state() != Invocation.State.SUCCEEDED || invocation.conversation() != null
                || !invocation.request().context().executionId().equals(trial.invocationId())
                || !trial.snapshotId().equals(invocation.contextSnapshotId()) || !matches(trial, snapshot)) {
            throw new PlatformException(PlatformErrorCodeEnum.INVALID_ARGUMENT, "assistant-trial");
        }
    }

    /**
     * 在事务内复核助手读取权限，按试用标识升序分页查询当前主体发起的试用记录。
     *
     * @param context     包含当前主体、租户和工作空间的可信执行上下文。
     * @param assistantId 要查询的助手资源标识。
     * @param after       上一页返回的试用标识游标，查询首页时为空。
     * @param limit       每页最多返回的试用记录数。
     * @return 本页试用历史及下一页游标，已到末页时游标为空。
     */
    public Mono<Page<TrialHistory>> trialHistory(
            ExecutionContext context, String assistantId, String after, int limit) {
        return executor.inTransaction(() -> {
            TrustedActor actor = lockActor(context);
            Scope scope = scope(context);
            Resource resource = store.find(new ResourceRef(scope.tenantId(), ASSISTANT, assistantId), true)
                    .filter(value -> value.scope().equals(scope)).orElseThrow(PlatformAssistantExecutionAccess::unavailable);
            require(actor, resource, ASSISTANT_READ);
            List<TrialHistory> items = store.trialHistory(assistantId, actor.principal().subjectId(), after, limit + 1);
            return items.size() <= limit ? new Page<>(items, null)
                    : new Page<>(items.subList(0, limit), items.get(limit - 1).trialId());
        });
    }

    /**
     * 读取会话绑定版本的助手摘要，并单独计算当前主体的使用资格和助手停用状态。
     * <p>
     * 调用方先校验会话读取权限。助手停用或使用权限撤销后，仍可通过有权读取的会话查看历史摘要。
     *
     * @param context 用于定位租户和工作空间并核对当前主体的可信执行上下文。
     * @param profile 已有会话绑定的精确聊天配置引用，普通会话传空。
     * @return 固定发布版本的摘要和当前状态。缺少助手引用、引用类型不符或找不到对应发布记录时返回空流。
     */
    public Mono<ConversationAssistant> conversationAssistant(ExecutionContext context, DefinitionRef profile) {
        if (profile == null || !"chat-profile".equals(profile.type())) {
            return Mono.empty();
        }
        return executor.read(() -> store.findByProfile(scope(context), profile.id(), false)
                .flatMap(resource -> store.release(resource.id(), DefaultAssistantManager.parseVersion(profile.version()))
                        .map(release -> {
                            ExecutionPrincipal principal = context.authorization().principal();
                            boolean available = resource.state() == State.ACTIVE && identities.findBySubjectId(principal.subjectId())
                                    .filter(principal::sameIdentityAs).filter(value -> value.status() == PrincipalStatusEnum.ACTIVE)
                                    .map(value -> authorization.check(new TrustedActor(value, clock.instant()),
                                            new AccessRequest(resource.scope(), resource.ref(), ASSISTANT_USE, "assistant-execution")).allowed())
                                    .orElse(false);
                            Content content = release.content();
                            return new ConversationAssistant(new Summary(release.assistantId(), content.displayName(),
                                    content.description(), content.examplePrompts(), profile), available, resource.state() == State.DISABLED);
                        }))
                .orElse(null));
    }

    /**
     * 在校验试用权限后锁定并读取草稿，确认修订号匹配且内容完整。
     */
    Draft draftForTrial(ExecutionContext context, String assistantId, long revision) {
        requireTrialAccess(context, assistantId);
        Draft draft = store.draft(assistantId, true).orElseThrow(PlatformAssistantExecutionAccess::unavailable);
        if (draft.revision() != revision) {
            throw new AdmissionException(ResultCodeEnum.AI_VERSION_CONFLICT);
        }
        DefaultAssistantManager.requireComplete(draft.content());
        return draft;
    }

    /**
     * 在当前写事务中锁定主体及工作空间的治理范围，并确认主体身份一致且仍处于启用状态。
     */
    TrustedActor lockActor(ExecutionContext context) {
        transactions.requireWritable();
        ExecutionPrincipal principal = context.authorization().principal();
        locks.scopes(principal.subjectId(), List.of(scope(context)));
        return identities.findCurrentBySubjectId(principal.subjectId()).filter(principal::sameIdentityAs)
                .filter(value -> value.status() == PrincipalStatusEnum.ACTIVE)
                .map(value -> new TrustedActor(value, clock.instant())).orElseThrow(PlatformAssistantExecutionAccess::unavailable);
    }

    /**
     * 校验助手归属当前工作空间，并要求当前主体同时具备编辑和使用权限。
     */
    private void requireTrialAccess(ExecutionContext context, String assistantId) {
        TrustedActor actor = lockActor(context);
        Scope scope = scope(context);
        Resource resource = store.find(new ResourceRef(scope.tenantId(), ASSISTANT, assistantId), true)
                .filter(value -> value.scope().equals(scope)).orElseThrow(PlatformAssistantExecutionAccess::unavailable);
        require(actor, resource, ASSISTANT_EDIT);
        require(actor, resource, ASSISTANT_USE);
    }

    /**
     * 在当前事务中校验指定助手操作的权限，将平台授权异常转换为助手配置不可用。
     */
    private void require(TrustedActor actor, Resource resource, Action action) {
        try {
            authorization.requireInCurrentTransaction(actor, new AccessRequest(resource.scope(), resource.ref(), action, "assistant-execution"));
        } catch (PlatformException exception) {
            throw unavailable();
        }
    }

    /**
     * 获取执行输入快照的持久化组件，组件缺失时报告试用能力未启用。
     */
    private MybatisPayloadPersistence payloadStore() {
        MybatisPayloadPersistence store = payloads.getIfAvailable();
        if (store == null) {
            throw new PlatformException(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED, "assistant-trial");
        }
        return store;
    }

    static Scope scope(ExecutionContext context) {
        return new Scope(context.authorization().tenantId(), context.authorization().workspaceId());
    }

    static String instruction(Content content) {
        return content.systemInstruction() + "\n\n预期输出\n" + content.expectedOutput();
    }

    static boolean matches(Trial trial, ContextSnapshot snapshot) {
        if (snapshot == null || snapshot.assistant() == null) {
            return false;
        }
        AssistantSource source = snapshot.assistant();
        return trial.id().equals(source.trialId()) && trial.assistantId().equals(source.assistantId())
                && trial.snapshotId().equals(snapshot.snapshotId()) && trial.draftRevision() == source.revision()
                && trial.contentDigest().equals(source.contentDigest());
    }

    private static boolean hasInstruction(ContextSnapshot snapshot, String text) {
        return snapshot.messages().getFirst().role() == ChatMessage.Role.SYSTEM
                && snapshot.messages().getFirst().content().equals(List.of(new ChatMessage.Text(text)));
    }

    private static AdmissionException unavailable() {
        return new AdmissionException(ResultCodeEnum.AI_CONFIGURATION_NOT_AVAILABLE);
    }
}
