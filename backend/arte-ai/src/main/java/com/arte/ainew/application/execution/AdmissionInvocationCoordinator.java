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
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ResolvedBinding;
import com.arte.ainew.pojo.execution.*;
import com.arte.ainew.pojo.generation.GenerationRequest;
import com.arte.ainew.spi.auth.ToolInvocationAccess;
import com.arte.ainew.spi.persistence.ContextSnapshotStore;
import com.arte.ainew.spi.persistence.ExecutionStore;
import com.arte.core.enums.ResultCodeEnum;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.util.List;

/**
 * 生成调用的可靠受理与耐久控制；只在真实事务提交后返回回执，实际派发由能力专属组件完成。
 * <p>
 * admission 意为“准入与可靠受理”。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
@Slf4j
public final class AdmissionInvocationCoordinator implements InvocationCoordinator {

    private final AdmissionAuthorization admissionAuthorization;
    private final CapabilityCatalog capabilityCatalog;
    private final BindingManager bindingManager;
    private final BudgetService budgetService;
    private final ContextSnapshotStore contextSnapshotStore;
    private final ExecutionStore executionStore;
    private final NewAiProperties properties;
    private final Clock clock;

    public AdmissionInvocationCoordinator(AdmissionAuthorization admissionAuthorization, CapabilityCatalog capabilityCatalog, BindingManager bindingManager,
                                          BudgetService budgetService, ContextSnapshotStore contextSnapshotStore, ExecutionStore executionStore,
                                          NewAiProperties properties, Clock clock) {
        this.admissionAuthorization = admissionAuthorization;
        this.capabilityCatalog = capabilityCatalog;
        this.bindingManager = bindingManager;
        this.budgetService = budgetService;
        this.contextSnapshotStore = contextSnapshotStore;
        this.executionStore = executionStore;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Mono<AcceptedExecution> submit(InvocationSubmission<?> submission) {
        return admissionAuthorization.prepare(submission.request().context(), submission.conversation() == null
                        ? java.util.Set.of(AdmissionAuthorization.INVOKE)
                        : java.util.Set.of(AdmissionAuthorization.INVOKE, AdmissionAuthorization.CONVERSATION), true, current -> {
                    if (!(submission.request().input() instanceof GenerationRequest generation)) {
                        throw new AdmissionException(ResultCodeEnum.AI_UNSUPPORTED_CAPABILITY);
                    }
                    var original = submission.request();
                    var request = new InvocationRequest<>(original.capability(), original.binding(), original.kind(), generation, original.options(), current);
                    var refreshed = new InvocationSubmission<>(request, submission.snapshot(), submission.conversation(),
                            submission.newTurn(), submission.replacesInvocationId());
                    return capabilityCatalog.validate(request).then(bindingManager.resolve(request.binding(), request.capability(), current))
                            .flatMap(binding -> admit(refreshed, binding));
                })
                .doOnError(error -> log.warn("AI admission failed, invocationId={}, traceId={}, capabilityId={}, code={}, type={}",
                        submission.request().context().executionId(), submission.request().context().traceId(), submission.request().capability().id(),
                        error instanceof AdmissionException rejected ? rejected.getResultCode().name() : "ADMISSION_FAILED",
                        error.getClass().getName()));
    }

    private Mono<NewAiProperties> configuration(ExecutionContext context) {
        return bindingManager instanceof com.arte.ainew.application.control.FixedControlCatalog catalog
                ? catalog.configuration(context) : Mono.just(properties);
    }

    private Mono<AcceptedExecution> admit(InvocationSubmission<GenerationRequest> submission, ResolvedBinding binding) {
        var request = submission.request();
        var snapshot = submission.snapshot();
        if (request.context().parentExecutionId() == null) TextInputs.verify(snapshot, binding, properties.limits());
        else if (!com.arte.ainew.application.tool.chat.ToolChatInputs.TOKENIZER.equals(snapshot.tokenizerVersion())
                || !snapshot.modelBinding().equals(binding.definition()) || submission.conversation() != null)
            throw new AdmissionException(ResultCodeEnum.AI_INVALID_CONTEXT_SNAPSHOT);
        if (request.input().options().maxOutputTokens() > snapshot.budget().reservedOutputTokens()) {
            throw new AdmissionException(ResultCodeEnum.AI_OUTPUT_RESERVATION_EXCEEDED);
        }
        if (submission.conversation() != null && submission.newTurn() == null) {
            throw new AdmissionException(ResultCodeEnum.AI_REGENERATION_NOT_SUPPORTED);
        }
        var turn = submission.newTurn();
        if (turn != null && submission.replacesInvocationId() == null && (turn.parentTurnId() != null || turn.supersedesTurnId() != null
                || turn.version() != 0 || turn.selectedInvocationId() != null
                || !turn.invocationIds().equals(List.of(request.context().executionId()))
                || !TextInputs.contentDigest(List.of(turn.userMessage())).equals(TextInputs.contentDigest(List.of(snapshot.messages().getLast()))))) {
            throw new AdmissionException(ResultCodeEnum.AI_INVALID_NEW_TURN);
        }
        var digest = AdmissionDigests.submission(submission);
        var owner = ExecutionOwner.from(request.context());
        return executionStore.findAccepted(owner, request.capability().id(), request.context().idempotencyKey())
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
                    return budgetService.account(owner, request.context().budgetRef())
                            .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_BUDGET_NOT_INITIALIZED)))
                            .flatMap(account -> configuration(request.context()).flatMap(configuration -> {
                                var definition = configuration.budgets().stream().filter(b ->
                                        b.budgetRef().equals(request.context().budgetRef())).findFirst().orElseThrow();
                                if (!account.budgetRef().equals(request.context().budgetRef())
                                        || !account.owner().equals(owner)
                                        || !account.rateVersion().equals(binding.rate())
                                        || !account.limit().currency().equals(definition.limit().currency())) {
                                    throw new AdmissionException(ResultCodeEnum.AI_BUDGET_CONFIGURATION_CONFLICT);
                                }
                                return contextSnapshotStore.put(owner, snapshot);
                            }))
                            .flatMap(saved -> {
                                if (!saved.successful()) {
                                    throw AdmissionException.fromStoreRejection(saved.code());
                                }
                                var candidate = new Invocation(request, digest, submission.conversation(), saved.value().snapshotId(), submission.replacesInvocationId(),
                                        Invocation.State.ACCEPTED, 0, null, null, null, now, now);
                                return configuration(request.context()).flatMap(configuration -> {
                                    var provider = configuration.connections().stream().filter(connection -> connection.definition().equals(binding.connection()))
                                            .findFirst().orElseThrow().providerId();
                                    return executionStore.accept(new ExecutionCommands.Accept(candidate, turn,
                                            new DispatchPlan(OutboxMessage.WorkClass.GENERATION, provider)));
                                });
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

    /**
     * 受理当前主体对既有 Invocation 的耐久控制命令；目前支持取消，其他命令由存储返回 UNSUPPORTED 回执。
     * <p>
     * 先按当前租户、空间和主体定位调用，再根据权威能力种类重新授权。非 TOOL 沿用 INVOKE 资格；
     * TOOL 可使用 READ 上下文完成已有操作的收尾，但仍须独立核验当前工具取消资格，READ 本身不授予取消权限。
     * TOOL 的最终授权回调交由控制存储在原写事务内再次执行，初次提交和幂等重放均不能绕过撤权。
     * <p>
     * 存储负责命令防重，以及回执、控制事件、取消标记和必要终态的原子提交；成功或重放返回耐久回执，
     * 存储拒绝转换为统一业务异常，基础设施异常继续传播。ACCEPTED 仅表示命令已保存，不证明运行中的工具已停止；
     * 实际终态及可能的 UNKNOWN 须查询原调用，本方法不直接取消业务订阅或重新派发。
     *
     * @param request 目标调用、控制类型及命令身份；commandKey 用于主体／目标／命令范围内的幂等处理
     * @param context 本次控制请求的可信身份、权限上限和期限，不沿用原调用可能已经到期的执行期限
     * @return 已持久化的控制回执；调用不存在、无权操作、存储拒绝或基础设施故障时返回错误信号
     */
    @Override
    public Mono<ControlReceipt> control(ExecutionControlRequest request, ExecutionContext context) {
        // 按完整 owner 定位；不存在和不属于当前主体均返回 NOT_FOUND，避免泄露其他主体的调用。
        return executionStore.find(ExecutionOwner.from(context), request.invocationId())
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND)))
                .flatMap(invocation -> {
                    if (invocation.request().kind() != CapabilityDescriptor.Kind.TOOL) {
                        // 非工具控制保持原 INVOKE 要求；requireWrite 强制重查当前授权，不复用展示读取快照。
                        return admissionAuthorization.requireWrite(context, AdmissionAuthorization.INVOKE)
                                .flatMap(current -> executionStore.requestControl(ExecutionOwner.from(current), request));
                    }

                    // TOOL 优先使用 READ 收尾，允许关闭 AI 新调用后停止已有操作；仅 INVOKE 的原入口仍可使用。
                    // TOOL 专用取消入口只申请 READ；如果这里固定要求 INVOKE，就可能因为新调用资格已关闭而无法取消。
                    var requiredScope = AdmissionAuthorization.INVOKE;
                    if (context.authorization().scopes().contains(AdmissionAuthorization.READ)) {
                        requiredScope = AdmissionAuthorization.READ;
                    }
                    return admissionAuthorization.requireWrite(context, requiredScope)
                            // 基础身份／空间资格通过后，另查工具 CANCEL 资格，不能仅凭 READ 取消。
                            .flatMap(current -> admissionAuthorization.toolInvocation(invocation, current, ToolInvocationAccess.Operation.CANCEL)
                                    // 在保存控制命令的同一物理事务内重验并持有公共资格锁，重放也执行。
                                    .flatMap(permit -> executionStore.requestControl(
                                            ExecutionOwner.from(current), request, permit::requireInCurrentTransaction)));
                })
                .flatMap(outcome -> {
                    // APPLIED／REPLAYED 均返回耐久回执；版本、归属等存储拒绝统一转换为业务异常。
                    if (outcome.successful()) {
                        return Mono.just(outcome.value());
                    }
                    return Mono.error(AdmissionException.fromStoreRejection(outcome.code()));
                });
    }
}
