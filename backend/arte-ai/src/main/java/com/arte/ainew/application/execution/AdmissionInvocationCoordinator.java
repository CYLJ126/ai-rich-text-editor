package com.arte.ainew.application.execution;

import com.arte.ainew.api.control.BindingManager;
import com.arte.ainew.api.control.CapabilityCatalog;
import com.arte.ainew.api.execution.BudgetService;
import com.arte.ainew.api.execution.InvocationCoordinator;
import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionDigests;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.support.TextInputs;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.spi.persistence.ContextSnapshotStore;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.core.enums.ResultCodeEnum;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;

/**
 * 第 1～3 步的可靠受理协调；只在真实受理事务成功后返回回执，派发／控制尚未启用。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
@Slf4j
public final class AdmissionInvocationCoordinator implements InvocationCoordinator {
    private final AdmissionAuthorization authorization;
    private final CapabilityCatalog capabilities;
    private final BindingManager bindings;
    private final BudgetService budgets;
    private final ContextSnapshotStore snapshots;
    private final ExecutionStore executions;
    private final NewAiProperties properties;
    private final Clock clock;

    public AdmissionInvocationCoordinator(AdmissionAuthorization authorization, CapabilityCatalog capabilities, BindingManager bindings,
                                          BudgetService budgets, ContextSnapshotStore snapshots, ExecutionStore executions,
                                          NewAiProperties properties, Clock clock) {
        this.authorization = authorization;
        this.capabilities = capabilities;
        this.bindings = bindings;
        this.budgets = budgets;
        this.snapshots = snapshots;
        this.executions = executions;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Mono<AcceptedExecution> submit(InvocationSubmission<?> submission) {
        return Mono.defer(() -> authorization.require(submission.request().context(), AdmissionAuthorization.INVOKE)
                .flatMap(current -> submission.conversation() == null ? Mono.just(current)
                        : authorization.require(current, AdmissionAuthorization.CONVERSATION))
                .flatMap(current -> {
                    if (!(submission.request().input() instanceof GenerationRequest generation)) {
                        throw new AdmissionException(ResultCodeEnum.AI_UNSUPPORTED_CAPABILITY);
                    }
                    var original = submission.request();
                    var request = new InvocationRequest<>(original.capability(), original.binding(), original.kind(), generation, original.options(), current);
                    var refreshed = new InvocationSubmission<>(request, submission.snapshot(), submission.conversation(),
                            submission.newTurn(), submission.replacesInvocationId());
                    return capabilities.validate(request).then(bindings.resolve(request.binding(), request.capability(), current))
                            .flatMap(binding -> admit(refreshed, binding));
                }))
                .doOnError(error -> log.warn("AI admission failed, invocationId={}, traceId={}, capabilityId={}, code={}, type={}",
                        submission.request().context().executionId(), submission.request().context().traceId(), submission.request().capability().id(),
                        error instanceof AdmissionException rejected ? rejected.getResultCode().name() : "ADMISSION_FAILED",
                        error.getClass().getName()));
    }

    private Mono<AcceptedExecution> admit(InvocationSubmission<GenerationRequest> submission, ResolvedBinding binding) {
        var request = submission.request();
        var snapshot = submission.snapshot();
        TextInputs.verify(snapshot, binding, properties.limits());
        if (request.input().options().maxOutputTokens() > snapshot.budget().reservedOutputTokens()) {
            throw new AdmissionException(ResultCodeEnum.AI_OUTPUT_RESERVATION_EXCEEDED);
        }
        if (submission.replacesInvocationId() != null || (submission.conversation() != null && submission.newTurn() == null)) {
            throw new AdmissionException(ResultCodeEnum.AI_REGENERATION_NOT_SUPPORTED);
        }
        var turn = submission.newTurn();
        if (turn != null && (turn.parentTurnId() != null || turn.supersedesTurnId() != null
                || turn.version() != 0 || turn.selectedInvocationId() != null
                || !turn.invocationIds().equals(List.of(request.context().executionId()))
                || !TextInputs.contentDigest(List.of(turn.userMessage())).equals(TextInputs.contentDigest(List.of(snapshot.messages().getLast()))))) {
            throw new AdmissionException(ResultCodeEnum.AI_INVALID_NEW_TURN);
        }
        var digest = AdmissionDigests.submission(submission);
        var owner = ExecutionOwner.from(request.context());
        return executions.findAccepted(owner, request.capability().id(), request.context().idempotencyKey())
                .flatMap(original -> {
                    if (!original.requestDigest().equals(digest)) {
                        log.warn("AI admission idempotency conflict, requestedInvocationId={}, originalInvocationId={}, traceId={}",
                                request.context().executionId(), original.request().context().executionId(), request.context().traceId());
                        throw new AdmissionException(ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT);
                    }
                    log.info("AI admission replayed, requestedInvocationId={}, originalInvocationId={}, traceId={}, state={}",
                            request.context().executionId(), original.request().context().executionId(), request.context().traceId(), original.state());
                    return Mono.just(receipt(original));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    var now = clock.instant();
                    if (!request.options().deadline().isAfter(now)) {
                        throw new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED);
                    }
                    if (!snapshot.expiresAt().isAfter(now) || snapshot.createdAt().isAfter(now)
                            || snapshot.expiresAt().isAfter(snapshot.createdAt().plus(properties.limits().snapshotRetention()))) {
                        throw new AdmissionException(ResultCodeEnum.AI_CONTEXT_EXPIRED_OR_INVALID);
                    }
                    return budgets.account(owner, request.context().budgetRef())
                            .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_BUDGET_NOT_INITIALIZED)))
                            .flatMap(account -> {
                                var definition = properties.budgets().stream().filter(b -> b.budgetRef().equals(request.context().budgetRef())).findFirst().orElseThrow();
                                if (!account.budgetRef().equals(request.context().budgetRef()) || !account.owner().equals(owner) || !account.rateVersion().equals(binding.rate())
                                        || !account.limit().currency().equals(definition.limit().currency())) {
                                    throw new AdmissionException(ResultCodeEnum.AI_BUDGET_CONFIGURATION_CONFLICT);
                                }
                                return snapshots.put(owner, snapshot);
                            })
                            .flatMap(saved -> {
                                if (!saved.successful()) {
                                    throw AdmissionException.fromStoreRejection(saved.code());
                                }
                                var candidate = new Invocation(request, digest, submission.conversation(), saved.value().snapshotId(), null,
                                        Invocation.State.ACCEPTED, 0, null, null, null, now, now);
                                return executions.accept(new ExecutionCommands.Accept(candidate, turn));
                            })
                            .map(accepted -> {
                                if (!accepted.successful()) {
                                    throw AdmissionException.fromStoreRejection(accepted.code());
                                }
                                var value = accepted.value();
                                log.info("AI admission committed, invocationId={}, traceId={}, snapshotId={}, conversationId={}, outcome={}, state={}",
                                        value.request().context().executionId(), request.context().traceId(), value.contextSnapshotId(),
                                        value.conversation() == null ? null : value.conversation().conversationId(), accepted.code(), value.state());
                                return receipt(accepted.value());
                            });
                }));
    }

    private static AcceptedExecution receipt(Invocation value) {
        return new AcceptedExecution(value.request().context().executionId(), AcceptedExecution.Kind.INVOCATION, value.acceptedAt());
    }

    @Override
    public Mono<Void> dispatch(OutboxMessage message, ExecutionRuntimeContext runtime) {
        return Mono.error(new AdmissionException(ResultCodeEnum.AI_DISPATCH_NOT_ENABLED));
    }

    @Override
    public Mono<Invocation> reconcile(ExecutionCommands.Version target, ExecutionRuntimeContext runtime) {
        return Mono.error(new AdmissionException(ResultCodeEnum.AI_RECONCILIATION_NOT_ENABLED));
    }

    @Override
    public Mono<ControlReceipt> control(ExecutionControlRequest request, ExecutionContext context) {
        return Mono.error(new AdmissionException(ResultCodeEnum.AI_CONTROL_NOT_ENABLED));
    }
}
