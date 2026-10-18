package com.arte.ainew.application.gateway;

import com.arte.ainew.application.tool.binding.ToolBindingService;
import com.arte.ainew.application.tool.discovery.LocalToolRegistry;
import com.arte.ainew.application.tool.schema.ToolSchemaValidator;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.common.execution.ExecutionOwner;
import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.context.ExecutionRuntimeContext;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.control.ExecutionTarget;
import com.arte.ainew.pojo.execution.GatewayCall;
import com.arte.ainew.pojo.execution.InvocationResult;
import com.arte.ainew.pojo.tool.*;
import com.arte.ainew.serialization.CanonicalJson;
import com.arte.ainew.serialization.ToolCatalogDigests;
import com.arte.ainew.spi.adapter.BusinessToolAdapter;
import com.arte.ainew.spi.auth.ToolExecutionAccess;
import com.arte.ainew.spi.gateway.ToolGateway;
import com.arte.ainew.spi.persistence.ExecutionRecordCodec;
import com.arte.ainew.spi.persistence.ToolCatalogStore;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.io.Serial;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 单次本地只读 TOOL 网关
 * <p>
 * 固定材料复核、当前授权、Schema、期限及输出检查，不重试或推进执行／预算状态。
 * 调用方必须先原子准备派发并持续维护领取／执行租约；网关内存状态不代替数据库 fencing。
 * 写工具、审批、网络与 Secret 尚未接线，遇到相关要求明确拒绝。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 15:21 ✾
 */
public final class DefaultToolGateway implements ToolGateway {

    private final ToolBindingService bindings;
    private final ToolCatalogStore catalog;
    private final LocalToolRegistry registry;
    private final ToolExecutionAccess access;
    private final ToolSchemaValidator schemas;
    private final ExecutionRecordCodec codec;
    private final Scheduler scheduler;
    private final Clock clock;

    /**
     * Scheduler 由组合模块管理；隔离 Schema 编译、同步适配入口及结果编码，不在构造时访问存储或执行工具。
     */
    public DefaultToolGateway(ToolBindingService bindings, ToolCatalogStore catalog, LocalToolRegistry registry,
                              ToolExecutionAccess access, ToolSchemaValidator schemas, ExecutionRecordCodec codec,
                              Scheduler scheduler, Clock clock) {
        this.bindings = Objects.requireNonNull(bindings);
        this.catalog = Objects.requireNonNull(catalog);
        this.registry = Objects.requireNonNull(registry);
        this.access = Objects.requireNonNull(access);
        this.schemas = Objects.requireNonNull(schemas);
        this.codec = Objects.requireNonNull(codec);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public Mono<ToolResult> invoke(GatewayCall<ToolInvocation> call) {
        Objects.requireNonNull(call);
        var subscribed = new AtomicBoolean();
        return Mono.defer(() -> {
            if (!subscribed.compareAndSet(false, true))
                return Mono.error(new IllegalStateException("Tool call publisher allows only one subscription"));
            // 0=尚未进入，1=已进入，2=已停止；取消与适配入口竞争同一 CAS，避免返回“未执行”后又进入业务。
            var entry = new AtomicInteger();
            var phase = new AtomicReference<>(ExecutionError.Phase.VALIDATION);
            var work = Mono.defer(() -> {
                // 检查是否被取消
                active(call);
                // 检查 attempt 租约是否过期，过期抛异常
                require(call.attempt().leaseExpiresAt().isAfter(clock.instant()), "TOOL_ATTEMPT_LEASE_EXPIRED");
                // fixed()：读取本次工具调用，并做相关校验
                // execute()：执行工具调用，并返回结果
                return fixed(call).flatMap(binding -> execute(call, binding, entry, phase));
            }).subscribeOn(scheduler);
            var remaining = Duration.between(clock.instant(), call.runtime().execution().deadline());
            Mono<ToolResult> deadline = remaining.isNegative() || remaining.isZero()
                    ? Mono.error(new TimeoutException())
                    : Mono.delay(remaining).flatMap(ignored -> Mono.error(new TimeoutException()));
            Mono<ToolResult> cancelled = call.runtime().cancellation().signal()
                    .flatMap(ignored -> Mono.error(new CancellationException()));
            return Mono.firstWithSignal(cancelled, deadline, work)
                    .onErrorResume(error -> {
                        entry.compareAndSet(0, 2);
                        boolean entered = entry.get() == 1;
                        return Mono.just(failure(call, code(error, entered), phase.get(), entered));
                    }).doOnCancel(() -> entry.compareAndSet(0, 2));
        });
    }

    /**
     * 以受理时固定的逻辑绝对期限为上限，附加从 Attempt 创建时刻计算的策略保护期限，覆盖 Schema／授权等待及执行。
     * 包含排队时间的产品总期限须由受理层固定到 ExecutionOptions；这里不重新分配逻辑 deadline。
     */
    private Mono<ToolResult> execute(GatewayCall<ToolInvocation> original, ToolBindingResolution binding,
                                     AtomicInteger entry, AtomicReference<ExecutionError.Phase> phase) {
        var before = original.runtime().execution();
        var deadline = before.deadline();
        var policyDeadline = original.attempt().createdAt().plus(binding.effectivePolicy().timeout());
        if (policyDeadline.isBefore(deadline)) deadline = policyDeadline;
        var context = new ExecutionContext(before.executionId(), before.traceId(), before.authorization(), deadline,
                before.parentExecutionId(), before.budgetRef(), before.releaseRef(), before.idempotencyKey());
        var call = new GatewayCall<>(original.request(), original.binding(), original.attempt(),
                new ExecutionRuntimeContext(context, original.runtime().cancellation()));
        active(call);
        return Mono.defer(() -> prepare(call, binding)).flatMap(prepared -> {
                    phase.set(ExecutionError.Phase.AUTHORIZATION);
                    return Mono.defer(() -> access.authorize(prepared.call()))
                            .switchIfEmpty(Mono.error(new Rejected("TOOL_ACCESS_DENIED")))
                            .map(authorization -> authorized(prepared, authorization));
                })
                // 授权或元数据读取可能等待；真正进入领域适配前再次复核治理／节点状态，不能重选版本。
                .flatMap(prepared -> fixed(prepared.call().call()).map(current -> {
                    require(current.equals(prepared.call().binding()), "TOOL_FIXED_BINDING_CHANGED");
                    return prepared;
                }))
                // 调用本地工具（适配器）
                .flatMap(prepared -> invokeAdapter(prepared, entry, phase))
                // 调用超时检查
                .timeout(remaining(call));
    }

    /**
     * 读取本次调用已经固定的工具绑定方案，并复核它当前是否仍可用、是否与调用一致：
     * 1. 读取固定方案，根据运行上下文的 owner 和请求中的绑定引用，读取之前保存的解析结果，沿用固定的工具版本、配置及目标，不重新选择新版；
     * 2. 复核当前可用性，委托 ToolBindingService.fixed 检查；
     * 3. 检查调用一致性，确认读取到的方案与 call.binding() 一致，并且方案中的工具引用与请求中的 input.tool() 一致；
     *
     * @param call 本次工具调用
     * @return 固定的工具绑定方案
     */
    private Mono<ToolBindingResolution> fixed(GatewayCall<ToolInvocation> call) {
        return bindings.fixed(ExecutionOwner.from(call.runtime().execution()), call.request().binding())
                .switchIfEmpty(Mono.error(new Rejected("TOOL_BINDING_UNAVAILABLE")))
                .map(result -> {
                    require(result.successful(), "TOOL_BINDING_UNAVAILABLE");
                    var binding = result.value();
                    require(binding.resolvedBinding().equals(call.binding())
                            && binding.tool().tool().definition().equals(call.request().input().tool()), "TOOL_BINDING_MISMATCH");
                    return binding;
                });
    }

    /**
     * 编译固定 Schema；不补默认值，不加载 URL，不把目录存在当作当前执行资格。
     */
    private Mono<Prepared> prepare(GatewayCall<ToolInvocation> call, ToolBindingResolution binding) {
        var tool = binding.tool().tool();
        var policy = binding.effectivePolicy();
        require(binding.tool().target() instanceof ExecutionTarget.Local, "TOOL_REMOTE_UNSUPPORTED");
        require(tool.risk().sideEffect() == CapabilityDescriptor.SideEffect.READ_ONLY
                        && !tool.risk().destructive() && policy.approvalMode() == ToolExecutionPolicy.ApprovalMode.NONE,
                "TOOL_APPROVAL_UNSUPPORTED");
        require(binding.networkOrigins().isEmpty() && tool.risk().allowedNetworkOrigins().isEmpty()
                && binding.secrets().isEmpty(), "TOOL_EXTERNAL_ACCESS_UNSUPPORTED");
        require(tool.capabilities().features().isEmpty() && !tool.capabilities().outcomeQuerySupported(), "TOOL_CAPABILITY_UNSUPPORTED");
        require(call.request().context().budgetRef() == null && call.runtime().execution().budgetRef() == null && call.attempt().budgetReservationId() == null
                && call.binding().rate() == null, "TOOL_CHARGE_UNSUPPORTED");
        require(call.attempt().attemptNumber() <= policy.maxAttempts(), "TOOL_ATTEMPT_LIMIT");
        require(call.runtime().execution().authorization().scopes().containsAll(tool.risk().requiredScopes()), "TOOL_ACCESS_DENIED");
        var adapter = registry.findAdapter(((ExecutionTarget.Local) binding.tool().target()).adapter())
                .orElseThrow(() -> new Rejected("TOOL_EXECUTOR_UNAVAILABLE"));
        return Mono.zip(schema(tool.inputSchema(), ToolSchemaValidator.Purpose.INPUT),
                        schema(tool.outputSchema(), ToolSchemaValidator.Purpose.OUTPUT),
                        schema(tool.configurationSchema(), ToolSchemaValidator.Purpose.CONFIGURATION))
                .publishOn(scheduler).map(compiled -> {
                    require(compiled.getT1().validate(call.request().input().arguments(), policy.maxInputBytes()).valid(), "TOOL_INPUT_INVALID");
                    require(compiled.getT3().validate(binding.effectiveConfiguration(), ToolExecutionPolicy.MAX_INPUT_BYTES).valid(),
                            "TOOL_CONFIGURATION_INVALID");
                    require(ToolCatalogDigests.configuration(binding.effectiveConfiguration()).equals(binding.configurationDigest()),
                            "TOOL_CONFIGURATION_INVALID");
                    return new Prepared(new LocalToolCall(call, binding), adapter, compiled.getT2());
                });
    }

    private Mono<ToolSchemaValidator.CompiledSchema> schema(DefinitionRef ref,
                                                            ToolSchemaValidator.Purpose purpose) {
        return catalog.findSchema(ref).switchIfEmpty(Mono.error(new Rejected("TOOL_SCHEMA_UNAVAILABLE")))
                .publishOn(scheduler).map(schema -> {
                    require(schema.definition().equals(ref) && ToolCatalogDigests.schema(schema.document()).equals(schema.checksum()),
                            "TOOL_SCHEMA_INVALID");
                    return schemas.compile(ref, schema.document(), purpose);
                });
    }

    private Prepared authorized(Prepared prepared, ExecutionAuthorization authorization) {
        var local = prepared.call();
        var original = local.call();
        var before = original.runtime().execution();
        require(authorization.principal().subjectId().equals(before.authorization().principal().subjectId())
                        && authorization.principal().kind() == before.authorization().principal().kind()
                        && authorization.tenantId().equals(before.authorization().tenantId())
                        && authorization.workspaceId().equals(before.authorization().workspaceId())
                        && before.authorization().scopes().containsAll(authorization.scopes())
                        && authorization.scopes().containsAll(local.binding().tool().tool().risk().requiredScopes()),
                "TOOL_ACCESS_DENIED");
        var context = new ExecutionContext(before.executionId(), before.traceId(), authorization, before.deadline(),
                before.parentExecutionId(), before.budgetRef(), before.releaseRef(), before.idempotencyKey());
        var current = new GatewayCall<>(original.request(), original.binding(), original.attempt(),
                new ExecutionRuntimeContext(context, original.runtime().cancellation()));
        return new Prepared(new LocalToolCall(current, local.binding()), prepared.adapter(), prepared.output());
    }

    private Mono<ToolResult> invokeAdapter(Prepared prepared, AtomicInteger entry, AtomicReference<ExecutionError.Phase> phase) {
        return Mono.defer(() -> {
            var call = prepared.call().call();
            active(call);
            phase.set(ExecutionError.Phase.INVOCATION);
            var remaining = remaining(call);
            // 标记先于适配器同步入口；入口抛错、空返回和超时均不能再宣称业务未执行。
            var result = Mono.defer(() -> {
                        active(call);
                        require(entry.compareAndSet(0, 1), "TOOL_CANCELLED");
                        // 调用本地工具 BusinessToolAdapter
                        return Objects.requireNonNull(prepared.adapter().invoke(prepared.call()));
                    }).subscribeOn(scheduler).switchIfEmpty(Mono.error(new Rejected("TOOL_RESULT_EMPTY")))
                    // 输出结果检查
                    .publishOn(scheduler).map(value -> {
                        phase.set(ExecutionError.Phase.OUTPUT);
                        require(value.callId().equals(call.request().input().callId())
                                && value.sideEffect() == ExecutionError.SideEffect.NONE, "TOOL_RESULT_INVALID");
                        // 最大输出字节检查
                        long maxBytes = prepared.call().binding().effectivePolicy().maxOutputBytes();
                        if (value.output() != null) {
                            require(prepared.output().validate(value.output(), maxBytes).valid(), "TOOL_OUTPUT_INVALID");
                        }
                        long max = Math.min(call.request().options().maxOutputBytes(), maxBytes);
                        require(CanonicalJson.normalize(codec.encode(new InvocationResult.Tool(value)))
                                .getBytes(StandardCharsets.UTF_8).length <= max, "TOOL_OUTPUT_LIMIT");
                        return value;
                    });
            // 调用超时检查
            return result.timeout(remaining);
        });
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new Rejected(code);
    }

    private void active(GatewayCall<ToolInvocation> call) {
        try {
            call.runtime().checkActive(clock);
        } catch (TimeoutException expired) {
            throw new Rejected("TOOL_DEADLINE_EXCEEDED");
        }
    }

    /**
     * 计算并检查剩余时间，如果剩余时间小于等于零则抛出工具调用超时异常。
     *
     * @param call 本次工具调用
     * @return 剩余时间
     */
    private Duration remaining(GatewayCall<ToolInvocation> call) {
        var remaining = Duration.between(clock.instant(), call.runtime().execution().deadline());
        require(!remaining.isNegative() && !remaining.isZero(), "TOOL_DEADLINE_EXCEEDED");
        return remaining;
    }

    private static String code(Throwable error, boolean entered) {
        if (error instanceof Rejected rejected) return rejected.getMessage();
        if (error instanceof TimeoutException) return "TOOL_DEADLINE_EXCEEDED";
        if (error instanceof CancellationException) return "TOOL_CANCELLED";
        if (error instanceof AccessDeniedException) return "TOOL_ACCESS_DENIED";
        return entered ? "TOOL_INVOCATION_ERROR" : "TOOL_PREFLIGHT_ERROR";
    }

    private static ToolResult failure(GatewayCall<ToolInvocation> call, String code, ExecutionError.Phase phase, boolean entered) {
        var sideEffect = entered ? ExecutionError.SideEffect.POSSIBLE : ExecutionError.SideEffect.NONE;
        var error = new ExecutionError(code, phase, false, sideEffect,
                entered ? ExecutionError.Certainty.UNKNOWN : ExecutionError.Certainty.KNOWN, call.request().context().traceId());
        return new ToolResult(call.request().input().callId(), entered ? ToolResult.Outcome.UNKNOWN : ToolResult.Outcome.FAILED,
                null, List.of(), List.of(), sideEffect, error);
    }

    /**
     * 本次已验证的固定调用、显式适配器及输出校验计划；授权更新只替换运行上下文，不更换配置或目标。
     */
    private record Prepared(LocalToolCall call, BusinessToolAdapter adapter,
                            ToolSchemaValidator.CompiledSchema output) {
    }

    /**
     * 只携带稳定安全码，不包含输入、配置或底层异常信息。
     */
    private static final class Rejected extends RuntimeException {

        @Serial
        private static final long serialVersionUID = 6111032855009305556L;

        private Rejected(String code) {
            super(code);
        }
    }
}
