package com.arte.app.platform.assistant;

import com.arte.ainew.application.assistant.DefaultAssistantManager;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
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

import static com.arte.core.constant.platform.PlatformActions.ASSISTANT_EDIT;
import static com.arte.core.constant.platform.PlatformActions.ASSISTANT_USE;
import static com.arte.core.constant.platform.PlatformResourceTypes.ASSISTANT;

/**
 * Revalidates assistant eligibility under the same workspace gate as management writes.
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

    private Prompt published(ExecutionContext context, DefinitionRef profile, boolean newConversation) {
        Scope scope = scope(context);
        TrustedActor actor = lockActor(context);
        var resource = store.findByProfile(scope, profile.id(), true).orElseThrow(PlatformAssistantExecutionAccess::unavailable);
        require(actor, resource, ASSISTANT_USE);
        long version = DefaultAssistantManager.parseVersion(profile.version());
        if (resource.state() != State.ACTIVE || newConversation && !Objects.equals(version, resource.releaseVersion())) {
            throw unavailable();
        }
        var release = store.release(resource.id(), version).orElseThrow(PlatformAssistantExecutionAccess::unavailable);
        return new Prompt(new AssistantSource(resource.id(), profile, null, release.draftRevision(),
                release.contentDigest(), release.content().displayName(), null), instruction(release.content()));
    }

    @Override
    public void requireCurrent(ExecutionContext context, ContextSnapshot snapshot) {
        var source = snapshot.assistant();
        if (source.profile() != null) {
            var prompt = published(context, source.profile(), false);
            if (!source.equals(prompt.source()) || !hasInstruction(snapshot, prompt.instruction())) throw unavailable();
            return;
        }
        requireTrialAccess(context, source.assistantId());
        var trial = store.trial(source.assistantId(), source.trialId()).orElseThrow(PlatformAssistantExecutionAccess::unavailable);
        var saved = payloadStore().findInCurrentTransaction(ExecutionOwners.from(context), trial.snapshotId());
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
        var owner = new ExecutionOwner(scope.tenantId(), scope.workspaceId(), trial.actorId());
        var store = executions.getIfAvailable();
        if (store == null) throw new PlatformException(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED, "assistant-trial");
        var invocation = store.findInCurrentTransaction(owner, trial.invocationId());
        var snapshot = payloadStore().findInCurrentTransaction(owner, trial.snapshotId());
        if (invocation == null || invocation.state() != Invocation.State.SUCCEEDED || invocation.conversation() != null
                || !invocation.request().context().executionId().equals(trial.invocationId())
                || !trial.snapshotId().equals(invocation.contextSnapshotId()) || !matches(trial, snapshot)) {
            throw new PlatformException(PlatformErrorCodeEnum.INVALID_ARGUMENT, "assistant-trial");
        }
    }

    Draft draftForTrial(ExecutionContext context, String assistantId, long revision) {
        requireTrialAccess(context, assistantId);
        var draft = store.draft(assistantId, true).orElseThrow(PlatformAssistantExecutionAccess::unavailable);
        if (draft.revision() != revision) throw new AdmissionException(ResultCodeEnum.AI_VERSION_CONFLICT);
        DefaultAssistantManager.requireComplete(draft.content());
        return draft;
    }

    TrustedActor lockActor(ExecutionContext context) {
        transactions.requireWritable();
        var principal = context.authorization().principal();
        locks.scopes(principal.subjectId(), List.of(scope(context)));
        return identities.findCurrentBySubjectId(principal.subjectId()).filter(principal::sameIdentityAs)
                .filter(value -> value.status() == PrincipalStatusEnum.ACTIVE)
                .map(value -> new TrustedActor(value, clock.instant())).orElseThrow(PlatformAssistantExecutionAccess::unavailable);
    }

    private void requireTrialAccess(ExecutionContext context, String assistantId) {
        var actor = lockActor(context);
        Scope scope = scope(context);
        var resource = store.find(new ResourceRef(scope.tenantId(), ASSISTANT, assistantId), true)
                .filter(value -> value.scope().equals(scope)).orElseThrow(PlatformAssistantExecutionAccess::unavailable);
        require(actor, resource, ASSISTANT_EDIT);
        require(actor, resource, ASSISTANT_USE);
    }

    private void require(TrustedActor actor, Resource resource, Action action) {
        try {
            authorization.requireInCurrentTransaction(actor, new AccessRequest(resource.scope(), resource.ref(), action, "assistant-execution"));
        } catch (PlatformException exception) {
            throw unavailable();
        }
    }

    private MybatisPayloadPersistence payloadStore() {
        var store = payloads.getIfAvailable();
        if (store == null) throw new PlatformException(PlatformErrorCodeEnum.CAPABILITY_NOT_ENABLED, "assistant-trial");
        return store;
    }

    static Scope scope(ExecutionContext context) {
        return new Scope(context.authorization().tenantId(), context.authorization().workspaceId());
    }

    static String instruction(Content content) {
        return content.systemInstruction() + "\n\n预期输出\n" + content.expectedOutput();
    }

    static boolean matches(Trial trial, ContextSnapshot snapshot) {
        if (snapshot == null || snapshot.assistant() == null) return false;
        var source = snapshot.assistant();
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
