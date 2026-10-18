package com.arte.ainew.application.tool.admission;

import com.arte.ainew.application.auth.AdmissionAuthorization;
import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.application.tool.binding.ToolBindingConstraints;
import com.arte.ainew.application.tool.binding.ToolBindingService;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.AcceptedExecution;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.execution.ExecutionOptions;
import com.arte.ainew.pojo.execution.InvocationRequest;
import com.arte.ainew.pojo.execution.StoreOutcome;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.ToolAdmissionDigests;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.spi.auth.ToolAdmissionAccess;
import com.arte.ainew.spi.persistence.ToolAdmissionStore;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import com.arte.core.enums.ResultCodeEnum;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;

/**
 * 独立本地免费只读 TOOL 的内部可靠受理
 * <p>
 * 不调用 Gateway、不创建 Attempt，也不选择模型或预算。
 * 先按稳定绑定定位重放，再对原材料重新授权；新调用固定参数／配置／Schema／总期限后经同库事务提交。
 * 宿主执行授权及 TOOL Worker 路由尚未接线，不能把本组件直接作为生产公开入口。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 16:30 ✾
 */
public final class ToolAdmissionService {

    private final ToolBindingService bindings;
    private final ToolCatalogStore catalog;
    private final ToolAdmissionStore store;
    private final ToolAdmissionAccess access;
    private final ToolSchemaValidator schemas;
    private final ToolExecutionPolicy limits;
    private final Scheduler scheduler;
    private final Clock clock;

    /**
     * 注入当前服务端上限及受信宿主授权；构造零 SQL／零工具调用，Scheduler 由容器或调用方管理。
     */
    public ToolAdmissionService(ToolBindingService bindings, ToolCatalogStore catalog, ToolAdmissionStore store,
                                ToolAdmissionAccess access, ToolSchemaValidator schemas, ToolExecutionPolicy limits,
                                Scheduler scheduler, Clock clock) {
        this.bindings = Objects.requireNonNull(bindings);
        this.catalog = Objects.requireNonNull(catalog);
        this.store = Objects.requireNonNull(store);
        this.access = Objects.requireNonNull(access);
        this.schemas = Objects.requireNonNull(schemas);
        this.limits = Objects.requireNonNull(limits);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.clock = Objects.requireNonNull(clock);
    }

    /**
     * 当前授权且真实受理事务提交成功后才返回回执；相同意图重放返回原身份和原受理时间。
     */
    public Mono<AcceptedExecution> submit(ToolAdmissionInput input, ExecutionContext context) {
        Objects.requireNonNull(input);
        Objects.requireNonNull(context);
        return Mono.defer(() -> {
            require(context.budgetRef() == null && context.parentExecutionId() == null, ResultCodeEnum.AI_TOOL_POLICY_DENIED);
            require(context.authorization().scopes().contains(AdmissionAuthorization.INVOKE), ResultCodeEnum.AI_TOOL_POLICY_DENIED);
            var received = clock.instant();
            var timeout = min(limits.timeout(), input.requestedTimeout() == null ? limits.timeout() : input.requestedTimeout());
            var deadline = earlier(context.deadline(), received.plus(timeout));
            var owner = ExecutionOwner.from(context);
            // 在调用线程只记录入口时刻，阻塞工作随后排入有界调度器；排队不能推迟总期限起点。
            var work = Mono.defer(() -> store.findAccepted(owner, input.bindingId(), context.idempotencyKey())
                            .flatMap(previous -> replay(input, context, previous))
                            .switchIfEmpty(Mono.defer(() -> bindings.resolve(owner, input.bindingId())
                                    .flatMap(result -> {
                                        require(result.successful(), ResultCodeEnum.AI_TOOL_NOT_AVAILABLE);
                                        return fresh(input, context, result.value(), received, deadline);
                                    }).switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_TOOL_NOT_AVAILABLE))))))
                    .subscribeOn(scheduler);
            return work.timeout(remaining(deadline)).onErrorMap(TimeoutException.class,
                    expired -> new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED));
        });
    }

    /**
     * 原调用即使已结束／过期也可在当前资格下重放；不重算原 deadline 或跟随新发布版本。
     */
    private Mono<AcceptedExecution> replay(ToolAdmissionInput input, ExecutionContext context, ToolAdmissionStore.Accepted previous) {
        var snapshot = previous.snapshot();
        return bindings.fixed(ExecutionOwner.from(context), snapshot.binding().definition()).flatMap(result -> {
            require(result.successful() && result.value().equals(snapshot.binding()), ResultCodeEnum.AI_TOOL_NOT_AVAILABLE);
            return permit(input, context, snapshot.binding()).flatMap(permit -> {
                require(snapshot.submissionDigest().equals(ToolAdmissionDigests.submission(ExecutionOwner.from(context), input)),
                        ResultCodeEnum.AI_IDEMPOTENCY_CONFLICT);
                return commit(snapshot, permit);
            });
        }).switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_TOOL_NOT_AVAILABLE)));
    }

    private Mono<AcceptedExecution> fresh(ToolAdmissionInput input, ExecutionContext original, ToolBindingResolution binding,
                                          Instant received, Instant entryDeadline) {
        ToolExecutionPolicy policy;
        try {
            policy = ToolBindingConstraints.intersect(binding.effectivePolicy(), limits);
        } catch (IllegalArgumentException conflict) {
            return Mono.error(new AdmissionException(ResultCodeEnum.AI_TOOL_POLICY_DENIED));
        }
        var totalTimeout = min(policy.timeout(), input.requestedTimeout() == null ? policy.timeout() : input.requestedTimeout());
        var deadline = earlier(entryDeadline, received.plus(totalTimeout));
        return documents(binding).publishOn(scheduler).flatMap(documents -> {
            validate(input, binding, documents, policy);
            return permit(input, original, binding).flatMap(permit -> {
                remaining(deadline);
                var context = new ExecutionContext(original.executionId(), original.traceId(), permit.authorization(), deadline,
                        null, null, original.releaseRef(), original.idempotencyKey());
                var invocation = new ToolInvocation(ToolAdmissionDigests.callId(binding.owner(), input.bindingId(), context.idempotencyKey()),
                        binding.tool().tool().definition(), input.arguments());
                var options = new ExecutionOptions(deadline, Math.min(policy.maxAttempts(), input.maxAttempts() == null ? policy.maxAttempts() : input.maxAttempts()),
                        Math.min(policy.maxOutputBytes(), input.maxOutputBytes() == null ? policy.maxOutputBytes() : input.maxOutputBytes()), 0, 0, totalTimeout);
                var request = new InvocationRequest<>(binding.tool().tool().capability(), binding.definition(), invocation.kind(), invocation, options, context);
                return commit(new ToolExecutionSnapshot(input, request, binding, policy, documents, received), permit);
            });
        }).timeout(remaining(deadline));
    }

    private Mono<VerifiedPermit> permit(ToolAdmissionInput input, ExecutionContext context, ToolBindingResolution binding) {
        return Mono.defer(() -> access.authorizeFreeReadOnly(input, context, binding))
                .switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_TOOL_POLICY_DENIED)))
                .map(permit -> {
                    var current = Objects.requireNonNull(permit.authorization());
                    var before = context.authorization();
                    require(current.principal().subjectId().equals(before.principal().subjectId()) && current.principal().kind() == before.principal().kind()
                            && current.tenantId().equals(before.tenantId()) && current.workspaceId().equals(before.workspaceId())
                            && before.scopes().containsAll(current.scopes()) && current.scopes().contains(AdmissionAuthorization.INVOKE)
                            && current.scopes().containsAll(binding.tool().tool().risk().requiredScopes()), ResultCodeEnum.AI_TOOL_POLICY_DENIED);
                    return new VerifiedPermit(permit, current);
                });
    }

    private Mono<AcceptedExecution> commit(ToolExecutionSnapshot snapshot, VerifiedPermit permit) {
        // 进入物理写事务后再次复核当前宿主资格；存储负责原子治理检查、幂等仲裁及全部持久化。
        return store.accept(snapshot, permit.permit()::requireInCurrentTransaction).map(result -> {
                    if (!result.successful()) {
                        if (result.code() == StoreOutcome.Code.VERSION_CONFLICT)
                            throw new AdmissionException(ResultCodeEnum.AI_TOOL_PLAN_CHANGED);
                        throw AdmissionException.fromStoreRejection(result.code());
                    }
                    var accepted = result.value().invocation();
                    return new AcceptedExecution(accepted.request().context().executionId(), AcceptedExecution.Kind.INVOCATION, accepted.acceptedAt());
                }).switchIfEmpty(Mono.error(new IllegalStateException("Tool admission store returned no outcome")))
                .onErrorMap(ToolAdmissionStore.DeadlineExceeded.class, expired -> new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED));
    }

    /**
     * 缓存已核验权限，防止 Permit 实现的多次 getter 返回不同授权。
     */
    private record VerifiedPermit(ToolAdmissionAccess.Permit permit, ExecutionAuthorization authorization) {
    }

    private Mono<List<ToolSchemaDefinition>> documents(ToolBindingResolution binding) {
        var tool = binding.tool().tool();
        return Flux.fromIterable(new LinkedHashSet<>(List.of(tool.inputSchema(), tool.outputSchema(), tool.configurationSchema())))
                .concatMap(ref -> catalog.findSchema(ref).switchIfEmpty(Mono.error(new AdmissionException(ResultCodeEnum.AI_TOOL_NOT_AVAILABLE))))
                .collectList();
    }

    /**
     * 当前阶段不开放审批、外发、付费或协议特性；受理不把只读声明当作资源权限。
     */
    private void validate(ToolAdmissionInput input, ToolBindingResolution binding, List<ToolSchemaDefinition> documents, ToolExecutionPolicy policy) {
        var tool = binding.tool().tool();
        require(binding.tool().target() instanceof ExecutionTarget.Local
                        && tool.risk().sideEffect() == CapabilityDescriptor.SideEffect.READ_ONLY
                        && !tool.risk().destructive() && policy.approvalMode() == ToolExecutionPolicy.ApprovalMode.NONE
                        && tool.capabilities().features().isEmpty() && !tool.capabilities().outcomeQuerySupported()
                        && binding.secrets().isEmpty() && binding.networkOrigins().isEmpty() && tool.risk().allowedNetworkOrigins().isEmpty(),
                ResultCodeEnum.AI_TOOL_POLICY_DENIED);
        for (var document : documents)
            if (!ToolCatalogDigests.schema(document.document()).equals(document.checksum()))
                throw new IllegalStateException("Tool admission schema is corrupt");
        require(compiled(documents, tool.inputSchema(), ToolSchemaValidator.Purpose.INPUT).validate(input.arguments(), policy.maxInputBytes()).valid(),
                ResultCodeEnum.AI_TOOL_INVALID_REQUEST);
        require(compiled(documents, tool.configurationSchema(), ToolSchemaValidator.Purpose.CONFIGURATION)
                        .validate(binding.effectiveConfiguration(), limits.maxInputBytes()).valid()
                        && ToolCatalogDigests.configuration(binding.effectiveConfiguration()).equals(binding.configurationDigest()),
                ResultCodeEnum.AI_TOOL_CONFIGURATION_INVALID);
        compiled(documents, tool.outputSchema(), ToolSchemaValidator.Purpose.OUTPUT);
    }

    private ToolSchemaValidator.CompiledSchema compiled(List<ToolSchemaDefinition> documents, DefinitionRef ref, ToolSchemaValidator.Purpose purpose) {
        var document = documents.stream().filter(item -> item.definition().equals(ref)).findFirst().orElseThrow();
        return schemas.compile(ref, document.document(), purpose);
    }

    private Duration remaining(Instant deadline) {
        var remaining = Duration.between(clock.instant(), deadline);
        require(!remaining.isNegative() && !remaining.isZero(), ResultCodeEnum.AI_DEADLINE_EXCEEDED);
        return remaining;
    }

    private static void require(boolean value, ResultCodeEnum code) {
        if (!value) throw new AdmissionException(code);
    }

    private static Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    private static Instant earlier(Instant first, Instant second) {
        return first.isBefore(second) ? first : second;
    }
}
