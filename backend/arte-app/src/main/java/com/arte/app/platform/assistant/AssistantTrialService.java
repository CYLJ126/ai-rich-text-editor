package com.arte.app.platform.assistant;

import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.assistant.DefaultAssistantManager;
import com.arte.ainew.application.context.TextContextService;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.common.validation.ContractChecks;
import com.arte.ainew.config.NewAiExecutionProperties;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.persistence.mybatis.MybatisExecutionPersistence;
import com.arte.ainew.persistence.mybatis.MybatisPayloadPersistence;
import com.arte.ainew.pojo.assistant.Assistants.Draft;
import com.arte.ainew.pojo.assistant.Assistants.Trial;
import com.arte.ainew.pojo.context.AssistantSource;
import com.arte.ainew.pojo.context.ContextBudget;
import com.arte.ainew.pojo.context.ContextRequest;
import com.arte.ainew.pojo.context.ContextSnapshot;
import com.arte.ainew.pojo.execution.ExecutionOptions;
import com.arte.ainew.pojo.execution.InvocationRequest;
import com.arte.ainew.pojo.execution.InvocationSubmission;
import com.arte.ainew.pojo.generation.ChatMessage;
import com.arte.ainew.pojo.generation.GenerationOptions;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.spi.assistant.AssistantContextAccess.Prompt;
import com.arte.app.platform.persistence.JdbcAssistantStore;
import com.arte.app.platform.persistence.PlatformPersistenceExecutor;
import com.arte.core.enums.ResultCodeEnum;
import com.arte.core.pojo.reference.DefinitionRef;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Pins a private trial before admitting an ordinary text invocation.
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/11/7 00:45 ✾
 */
public final class AssistantTrialService {

    public record Request(String assistantId, long draftRevision, String text, DefinitionRef capability,
                          DefinitionRef binding, long maxInputTokens, GenerationOptions options, Duration timeout) {
        public Request {
            ContractChecks.id(assistantId, "assistantId");
            ContractChecks.require(draftRevision > 0, "Draft revision must be positive");
            ContractChecks.text(text, "text", ContractChecks.MAX_TEXT_CHARS);
        }
    }

    public record AcceptedTrial(String trialId, long draftRevision, AcceptedExecution execution) {
    }

    public record ExecutionAssistant(String assistantId, String displayName, DefinitionRef chatProfile,
                                     String trialId, Long draftRevision) {
    }

    public Mono<ExecutionAssistant> executionAssistant(String invocationId, ExecutionContext context) {
        var owner = ExecutionOwners.from(context);
        return executions.find(owner, invocationId)
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                .flatMap(invocation -> payloads.find(owner, invocation.contextSnapshotId()))
                .mapNotNull(snapshot -> {
                    var source = snapshot.assistant();
                    if (source == null) return null;
                    return new ExecutionAssistant(source.assistantId(), source.displayName(), source.profile(),
                            source.trialId(), source.trialId() == null ? null : source.revision());
                });
    }

    private record Frozen(Trial trial, ContextSnapshot snapshot, AcceptedExecution receipt) {
    }

    private record Preparation(Draft draft, Frozen existing) {
    }

    private final JdbcAssistantStore store;
    private final PlatformAssistantExecutionAccess access;
    private final PlatformPersistenceExecutor executor;
    private final MybatisPayloadPersistence payloads;
    private final MybatisExecutionPersistence executions;
    private final TextContextService contexts;
    private final BindingManager bindings;
    private final InvocationCoordinator coordinator;
    private final NewAiProperties properties;
    private final NewAiExecutionProperties execution;

    public AssistantTrialService(JdbcAssistantStore store, PlatformAssistantExecutionAccess access,
                                 PlatformPersistenceExecutor executor, MybatisPayloadPersistence payloads, MybatisExecutionPersistence executions,
                                 TextContextService contexts, BindingManager bindings, InvocationCoordinator coordinator,
                                 NewAiProperties properties, NewAiExecutionProperties execution) {
        this.store = store;
        this.access = access;
        this.executor = executor;
        this.payloads = payloads;
        this.executions = executions;
        this.contexts = contexts;
        this.bindings = bindings;
        this.coordinator = coordinator;
        this.properties = properties;
        this.execution = execution;
    }

    public Mono<AcceptedTrial> submit(Request request, ExecutionContext context) {
        ContractChecks.id(context.idempotencyKey(), "idempotencyKey");
        var owner = ExecutionOwners.from(context);
        String key = CanonicalJson.key("assistant-trial-v1", owner.tenantId(), owner.workspaceId(), owner.subjectId(), context.idempotencyKey());
        String digest = CanonicalJson.digest(CanonicalJson.fields("request", request, "budget", context.budgetRef()));
        return executor.inTransaction(() -> {
                    access.lockActor(context);
                    return existing(context, key, digest).map(value -> new Preparation(null, value))
                            .orElseGet(() -> new Preparation(access.draftForTrial(context, request.assistantId(), request.draftRevision()), null));
                }).flatMap(prepared -> prepared.existing() == null
                        ? freeze(request, context, prepared.draft(), key, digest) : Mono.just(prepared.existing()))
                .flatMap(frozen -> admit(request, context, key, frozen));
    }

    private Mono<Frozen> freeze(Request request, ExecutionContext context, Draft draft, String key, String digest) {
        var source = new AssistantSource(draft.assistantId(), null, UUID.randomUUID().toString(),
                draft.revision(), DefaultAssistantManager.contentDigest(draft.content()), draft.content().displayName(), context.deadline());
        var prompt = new Prompt(source, PlatformAssistantExecutionAccess.instruction(draft.content()));
        return bindings.resolve(request.binding(), request.capability(), context)
                .flatMap(binding -> {
                    long output = request.options().maxOutputTokens();
                    if (request.maxInputTokens() > binding.contextWindowTokens() - output) {
                        throw new AdmissionException(ResultCodeEnum.AI_CONTEXT_CAPACITY_EXCEEDED);
                    }
                    var message = new ChatMessage(UUID.randomUUID().toString(), ChatMessage.Role.USER,
                            List.of(new ChatMessage.Text(request.text())), List.of(), null);
                    var selection = new ContextRequest(List.of(message), null, List.of(), List.of(), null,
                            new ContextBudget(binding.contextWindowTokens(), request.maxInputTokens(), output, 0));
                    return contexts.assembleTrial(selection, binding, context, prompt);
                }).flatMap(snapshot -> executor.inTransaction(() -> {
                    access.lockActor(context);
                    var prior = existing(context, key, digest);
                    if (prior.isPresent()) return prior.get();
                    var current = access.draftForTrial(context, draft.assistantId(), draft.revision());
                    if (!current.content().equals(draft.content())) {
                        throw new AdmissionException(ResultCodeEnum.AI_VERSION_CONFLICT);
                    }
                    var owner = ExecutionOwners.from(context);
                    var saved = payloads.putInCurrentTransaction(owner, snapshot);
                    if (!saved.successful()) throw AdmissionException.fromStoreRejection(saved.code());
                    var trial = new Trial(source.trialId(), draft.assistantId(), draft.revision(), owner.subjectId(),
                            source.contentDigest(), snapshot.snapshotId(), context.executionId());
                    store.insertTrial(trial, key, digest, snapshot.createdAt());
                    return new Frozen(trial, saved.value(), null);
                }));
    }

    private Optional<Frozen> existing(ExecutionContext context, String key, String digest) {
        var owner = ExecutionOwners.from(context);
        return store.trialByKey(key).map(saved -> {
            if (!saved.requestDigest().equals(digest))
                throw new AdmissionException(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT);
            var trial = saved.trial();
            var snapshot = payloads.findInCurrentTransaction(owner, trial.snapshotId());
            if (!trial.actorId().equals(owner.subjectId()) || !PlatformAssistantExecutionAccess.matches(trial, snapshot)) {
                throw new AdmissionException(ResultCodeEnum.AI_INVALID_CONTEXT_SNAPSHOT);
            }
            var invocation = executions.findInCurrentTransaction(owner, trial.invocationId());
            if (invocation == null) return new Frozen(trial, snapshot, null);
            if (invocation.conversation() != null || !trial.snapshotId().equals(invocation.contextSnapshotId())) {
                throw new AdmissionException(ResultCodeEnum.AI_INVALID_CONTEXT_SNAPSHOT);
            }
            return new Frozen(trial, snapshot,
                    new AcceptedExecution(trial.invocationId(), AcceptedExecution.Kind.INVOCATION, invocation.acceptedAt()));
        });
    }

    private Mono<AcceptedTrial> admit(Request request, ExecutionContext context, String key, Frozen frozen) {
        var trial = frozen.trial();
        if (frozen.receipt() != null)
            return Mono.just(new AcceptedTrial(trial.id(), trial.draftRevision(), frozen.receipt()));
        var snapshot = frozen.snapshot();
        var deadline = snapshot.assistant().trialDeadline();
        if (context.deadline().isBefore(deadline)) deadline = context.deadline();
        var pinned = new ExecutionContext(trial.invocationId(), context.traceId(), context.authorization(), deadline, null,
                context.budgetRef(), context.releaseRef(), key);
        var generation = new GenerationRequest(snapshot.messages(), request.options(), List.of(), new GenerationRequest.TextOutput());
        var options = new ExecutionOptions(deadline, execution.retry().maxAttempts(), properties.limits().maxOutputBytes(), 0, 0, request.timeout());
        var invocation = new InvocationRequest<>(request.capability(), request.binding(), generation.kind(), generation, options, pinned);
        return coordinator.submit(new InvocationSubmission<>(invocation, snapshot, null, null, null))
                .map(accepted -> new AcceptedTrial(trial.id(), trial.draftRevision(), accepted));
    }
}
