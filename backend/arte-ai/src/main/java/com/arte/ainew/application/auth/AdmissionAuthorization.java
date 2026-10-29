package com.arte.ainew.application.auth;

import com.arte.ainew.application.support.AdmissionException;
import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.common.execution.ExecutionOwners;
import com.arte.ainew.config.NewAiProperties;
import com.arte.ainew.config.NewAiProperties.Grant;
import com.arte.ainew.context.ControlPreparation;
import com.arte.ainew.context.ReadOperationSnapshot;
import com.arte.ainew.pojo.control.CapabilityDescriptor;
import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.spi.auth.AiControlConfigurationSource;
import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
import com.arte.ainew.spi.auth.ExecutionReadAuthorizationResolver;
import com.arte.ainew.spi.auth.ToolInvocationAccess;
import com.arte.core.enums.ResultCodeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/**
 * 展示读取复用本次订阅的可信快照，写入／派发始终复核当前资格；任务权限上限不可扩大。
 * <p>
 * 典型用法是先检查操作权限，再检查资源使用范围：
 * authorization.require(context, AdmissionAuthorization.INVOKE)
 * .map(current -> {
 * var grant = authorization.grant(current);
 * // 再检查 grant.bindingIds() 是否包含目标绑定
 * return grant;
 * });
 * 例如，一个主体拥有 ai:invoke，说明它可以提交 AI 调用；但能调用哪个绑定、使用哪个预算，还要根据 grant 中的配置继续判断。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/5 16:22 ✾
 */
@Slf4j
public final class AdmissionAuthorization {
    /**
     * AI 调用权限：允许发现／解析可用能力、绑定和连接，组装输入上下文并提交调用。
     * 关联会话的提交还需 CONVERSATION 权限，实际可用绑定和预算仍由主体授权配置限制。
     */
    public static final String INVOKE = "ai:invoke";

    /**
     * 会话操作权限：允许创建会话、读取所属会话／Turn，以及将调用关联到会话。
     * 不授予其他主体会话的访问权，也不单独授予 AI 调用权限。
     */
    public static final String CONVERSATION = "ai:conversation";

    /**
     * 执行数据读取权限：读取所属上下文快照、执行状态、结果、事件及授权预算账户，不触发模型调用。
     * 会话／Turn 读取使用 CONVERSATION 权限；预算初始化另需 BUDGET_ADMIN。
     */
    public static final String READ = "ai:read";

    /**
     * 预算管理权限：当前用于显式初始化授权范围内、归属于当前主体的预算账户。
     * 独立于调用权限，不允许重置已有账本或管理其他主体账户。
     */
    public static final String BUDGET_ADMIN = "ai:budget:admin";

    private final ExecutionAuthorizationResolver resolver;

    private final NewAiProperties properties;

    private final Clock clock;
    private AiControlConfigurationSource source;
    private Scheduler scheduler;
    private ToolInvocationAccess toolInvocations;

    public AdmissionAuthorization(ExecutionAuthorizationResolver resolver, NewAiProperties properties, Clock clock) {
        this.resolver = Objects.requireNonNull(resolver);
        this.properties = properties;
        this.clock = clock;
    }

    public AdmissionAuthorization(ExecutionAuthorizationResolver resolver, NewAiProperties properties, Clock clock,
                                  AiControlConfigurationSource source, Scheduler scheduler) {
        this(resolver, properties, clock);
        this.source = source;
        this.scheduler = scheduler;
    }

    public AdmissionAuthorization(ExecutionAuthorizationResolver resolver, NewAiProperties properties, Clock clock,
                                  AiControlConfigurationSource source, Scheduler scheduler,
                                  ToolInvocationAccess toolInvocations) {
        this(resolver, properties, clock, source, scheduler);
        this.toolInvocations = toolInvocations;
    }

    /**
     * 通用 READ 不自动授予 TOOL 结果读取权；每次返回状态、结果、事件或刷新订阅时复核当前工具资格。
     */
    public Mono<Invocation> readInvocation(Invocation invocation, ExecutionContext context) {
        if (invocation.request().kind() != CapabilityDescriptor.Kind.TOOL) {
            return Mono.just(invocation);
        }
        return toolInvocation(invocation, context, ToolInvocationAccess.Operation.READ).thenReturn(invocation);
    }

    /**
     * 有界工具历史批量读取；逐条验证可信记录归属和宿主许可，公共资格可由宿主在同一批内合并读取。
     * 不读取客户端提供的调用快照，不将此许可用于取消或业务写入。
     */
    public Mono<List<Invocation>> readInvocations(List<Invocation> invocations, ExecutionContext context) {
        return Mono.defer(() -> {
            var records = List.copyOf(invocations);
            if (records.size() > 100) {
                return Mono.error(new IllegalArgumentException("Tool read batch exceeds limit"));
            }
            var remaining = Duration.between(clock.instant(), context.deadline());
            if (remaining.isNegative() || remaining.isZero()) {
                return Mono.error(new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED));
            }
            var inputs = new HashMap<String, Invocation>();
            for (var invocation : records) {
                if (invocation.request().kind() != CapabilityDescriptor.Kind.TOOL
                        || !ExecutionOwners.from(invocation.request().context()).equals(ExecutionOwners.from(context))
                        || inputs.put(invocation.request().context().executionId(), invocation) != null) {
                    return Mono.error(new IllegalArgumentException("Invalid tool read batch"));
                }
            }
            if (records.isEmpty()) {
                return Mono.just(List.of());
            }
            if (toolInvocations == null) {
                return Mono.error(new AccessDeniedException("Tool invocation access unavailable"));
            }
            return toolInvocations.authorizeReadBatch(records, context).timeout(remaining)
                    .switchIfEmpty(Mono.error(new AccessDeniedException("Tool invocation access denied")))
                    .onErrorResume(AccessDeniedException.class, ignored -> Mono.just(List.of()))
                    .map(permits -> {
                        var allowed = new HashSet<String>();
                        for (var permit : permits) {
                            var invocation = inputs.get(permit.invocationId());
                            if (invocation == null || !allowed.add(permit.invocationId())) {
                                throw new IllegalStateException("Tool read permit does not match batch");
                            }
                            requireToolPermit(invocation, context, permit.authorization(), ToolInvocationAccess.Operation.READ);
                        }
                        return records.stream().filter(i -> allowed.contains(i.request().context().executionId())).toList();
                    });
        });
    }

    /**
     * 校验宿主许可未更换主体／空间或扩大本次上下文；取消调用方仍须把许可交给写事务重验。
     */
    public Mono<ToolInvocationAccess.Permit> toolInvocation(Invocation invocation, ExecutionContext context,
                                                            ToolInvocationAccess.Operation operation) {
        return Mono.defer(() -> {
            var remaining = Duration.between(clock.instant(), context.deadline());
            if (remaining.isNegative() || remaining.isZero()) {
                return Mono.error(new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED));
            }
            if (!ExecutionOwners.from(invocation.request().context()).equals(ExecutionOwners.from(context))) {
                return Mono.error(new AdmissionException(ResultCodeEnum.AI_NOT_FOUND));
            }
            if (toolInvocations == null) {
                return Mono.error(new AccessDeniedException("Tool invocation access unavailable"));
            }
            return toolInvocations.authorize(invocation, context, operation)
                    .timeout(remaining)
                    .switchIfEmpty(Mono.error(new AccessDeniedException("Tool invocation access denied")))
                    .map(permit -> {
                        requireToolPermit(invocation, context, permit.authorization(), operation);
                        return permit;
                    });
        });
    }

    private void requireToolPermit(Invocation invocation, ExecutionContext context,
                                   ExecutionAuthorization current, ToolInvocationAccess.Operation operation) {
        Objects.requireNonNull(current);
        var original = context.authorization();
        if (!clock.instant().isBefore(context.deadline())) {
            throw new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED);
        }
        if (original.principal().kind() != invocation.request().context().authorization().principal().kind()
                || !current.scopes().contains(operation == ToolInvocationAccess.Operation.READ ? READ
                : original.scopes().contains(READ) ? READ : INVOKE)
                || !current.principal().equals(original.principal()) || !current.tenantId().equals(original.tenantId())
                || !current.workspaceId().equals(original.workspaceId()) || !original.scopes().containsAll(current.scopes())) {
            throw new AccessDeniedException("Tool invocation authorization changed");
        }
    }

    /**
     * 操作权限检查
     * <p>
     * 订阅内只读快照在入口已验证资格；没有快照时重新查询当前授权。
     * 权限、主体和总期限始终校验；写入／派发必须使用 requireWrite。
     *
     * @param context 执行上下文
     * @param scope   操作权限范围
     * @return 执行上下文
     */
    public Mono<ExecutionContext> require(ExecutionContext context, String scope) {
        return require(context, scope, Check.READ);
    }

    /**
     * 只读操作返回前重新查询当前资格，不复用入口快照或控制准备结果。
     * 保留只读快照的上下文边界；此复核不授予写入或派发权限。
     */
    public Mono<ExecutionContext> requireFreshRead(ExecutionContext context, String scope) {
        return require(context, scope, Check.FRESH_READ);
    }

    /**
     * 写操作与外部派发不得由订阅内展示快照授权，始终重新取得当前资格。
     */
    public Mono<ExecutionContext> requireWrite(ExecutionContext context, String scope) {
        return require(context, scope, Check.WRITE);
    }

    /**
     * 使用当前授权和本次订阅内的控制配置执行操作。
     * <p>
     * 新的受理或派发边界重新加载配置，并核验原上下文中的全部权限。
     * 操作内部的准备步骤可复用绑定到同一上下文的已有快照。
     * 只读快照不能用于写操作准备，{@link #requireWrite} 仍独立查询当前授权。
     * 未配置控制配置来源时，退回到一次新的写授权检查。
     *
     * @param context  可信执行上下文，保留其身份、权限上限和截止时间
     * @param required 本次操作所需的非空权限集合
     * @param fresh    是否重新读取当前资格和配置，而不复用已有准备结果
     * @param work     使用已验证上下文及准备配置执行的操作
     * @param <T>      操作结果类型
     * @return 操作结果，或授权、期限、准备过程中的错误
     */
    public <T> Mono<T> prepare(ExecutionContext context, Set<String> required, boolean fresh, Function<ExecutionContext, Mono<T>> work) {
        return Mono.deferContextual(values -> {
            if (!context.authorization().scopes().containsAll(required) || required.isEmpty()) {
                return Mono.error(new AccessDeniedException("Execution scope denied"));
            }
            var remaining = Duration.between(clock.instant(), context.deadline());
            if (remaining.isNegative() || remaining.isZero()) {
                return Mono.error(new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED));
            }
            if (ReadOperationSnapshot.find(values, context).isPresent()) {
                return Mono.error(new IllegalStateException("Read snapshot cannot prepare a write"));
            }
            if (!fresh && ControlPreparation.find(values, context).isPresent()) {
                return Mono.defer(() -> work.apply(context));
            }
            if (source == null) {
                return requireWrite(context, required.iterator().next()).flatMap(work);
            }
            return Mono.fromCallable(() -> source.prepareConfiguration(context)).subscribeOn(scheduler).timeout(remaining)
                    .flatMap(prepared -> withConfiguration(context, prepared, work));
        });
    }

    /**
     * 发送前重新授权；来源支持版本凭证时可省略未变化的目录和预算定义读取。
     *
     * @param context 需要重新验证的可信上下文
     * @param work    使用当前资格和配置执行的发送前校验
     * @param <T>     校验结果类型
     * @return 当前资格有效时的结果，或授权、配置、期限错误
     */
    public <T> Mono<T> revalidate(ExecutionContext context, Function<ExecutionContext, Mono<T>> work) {
        return Mono.deferContextual(values -> {
            var prepared = ControlPreparation.findPrepared(values, context);
            if (source == null || prepared.isEmpty()) {
                return prepare(context, java.util.Set.of(INVOKE), true, work);
            }
            if (ReadOperationSnapshot.find(values, context).isPresent()) {
                return Mono.error(new IllegalStateException("Read snapshot cannot authorize sending"));
            }
            if (!context.authorization().scopes().contains(INVOKE)) {
                return Mono.error(new AccessDeniedException("Execution scope denied"));
            }
            var remaining = Duration.between(clock.instant(), context.deadline());
            if (remaining.isNegative() || remaining.isZero()) {
                return Mono.error(new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED));
            }
            return Mono.fromCallable(() -> source.revalidate(context, prepared.get())).subscribeOn(scheduler).timeout(remaining)
                    .flatMap(current -> withConfiguration(context, current, work));
        });
    }

    private <T> Mono<T> withConfiguration(ExecutionContext context, AiControlConfigurationSource.PreparedConfiguration prepared,
                                          java.util.function.Function<ExecutionContext, Mono<T>> work) {
        var grant = grant(prepared.configuration(), context);
        if (!grant.scopes().containsAll(context.authorization().scopes())) {
            return Mono.error(new AccessDeniedException("Execution scope denied"));
        }
        if (!clock.instant().isBefore(context.deadline())) {
            return Mono.error(new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED));
        }
        var original = context.authorization();
        var authorization = new ExecutionAuthorization(
                original.principal(), original.tenantId(), original.workspaceId(), original.scopes(), grant.grantRef());
        var current = new ExecutionContext(context.executionId(), context.traceId(), authorization,
                context.deadline(), context.parentExecutionId(), context.budgetRef(), context.releaseRef(), context.idempotencyKey());
        return ControlPreparation.use(current, prepared, work);
    }

    private enum Check {READ, FRESH_READ, WRITE}

    private Mono<ExecutionContext> require(ExecutionContext context, String scope, Check check) {
        Mono<ExecutionContext> result = Mono.deferContextual(values -> {
            var original = Objects.requireNonNull(context).authorization();
            // 检查上下文是否包含所需权限，例如 ai:invoke
            if (!original.scopes().contains(scope)) {
                log.warn("AI execution scope denied, invocationId={}, traceId={}, requiredScope={}", context.executionId(), context.traceId(), scope);
                throw new AccessDeniedException("Execution scope denied");
            }
            // 检查执行期限是否已过期
            var remaining = Duration.between(clock.instant(), context.deadline());
            if (remaining.isNegative() || remaining.isZero()) {
                throw new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED);
            }
            if (ReadOperationSnapshot.find(values, context).isPresent()) {
                if (check == Check.WRITE) {
                    return Mono.error(new IllegalStateException("Read snapshot cannot authorize a write"));
                }
                if (check == Check.READ) {
                    return Mono.just(context);
                }
            }
            if (check == Check.READ && ControlPreparation.find(values, context).isPresent()) {
                return Mono.just(context);
            }
            // 通过 ExecutionAuthorizationResolver 重新查询当前授权，确认授权仍有效
            Mono<ExecutionAuthorization> resolved = check != Check.WRITE
                    && resolver instanceof ExecutionReadAuthorizationResolver reads
                    ? reads.readSnapshot(original.principal().subjectName(), original.tenantId(), original.workspaceId(), original.scopes(), false)
                    .map(ExecutionReadAuthorizationResolver.Snapshot::authorization)
                    : resolver.resolve(original.principal().subjectName(), original.tenantId(), original.workspaceId(), original.scopes());
            return resolved.timeout(remaining)
                    .switchIfEmpty(Mono.error(new AccessDeniedException("Execution authorization denied")))
                    .map(fresh -> {
                        // 确认主体、租户、工作空间和权限集合与原上下文一致
                        if (!fresh.principal().equals(original.principal()) || !fresh.tenantId().equals(original.tenantId())
                                || !fresh.workspaceId().equals(original.workspaceId()) || !fresh.scopes().equals(original.scopes())) {
                            log.warn("AI execution authorization changed, invocationId={}, traceId={}, requiredScope={}",
                                    context.executionId(), context.traceId(), scope);
                            throw new AccessDeniedException("Execution authorization changed");
                        }
                        if (!clock.instant().isBefore(context.deadline())) {
                            throw new AdmissionException(ResultCodeEnum.AI_DEADLINE_EXCEEDED);
                        }
                        // 返回更新了授权信息的上下文，保留原执行 ID、期限、预算等信息
                        return new ExecutionContext(context.executionId(), context.traceId(), fresh, context.deadline(),
                                context.parentExecutionId(), context.budgetRef(), context.releaseRef(), context.idempotencyKey());
                    });
        });
        return source == null ? result : result.publishOn(scheduler);
    }

    /**
     * 固定授权配置查询，直接返回 NewAiProperties.Grant
     * <p>
     * 根据上下文中的主体 ID、名称、类型、租户和工作空间，查找处于启用状态的配置；找不到就抛出 AccessDeniedException。
     * 调用方据此判断该主体能否使用某个绑定或预算。固定文件模式不重新查询授权，数据库来源会重查当前公共资格；此方法不检查执行期限。
     *
     * @param context 执行上下文
     * @return 授权配置（包含 bindingIds、budgetRefs 等信息）
     */
    public Grant grant(ExecutionContext context) {
        if (source != null) {
            return source.grant(context);
        }
        return grant(properties, context);
    }

    public Mono<Grant> configurationGrant(ExecutionContext context) {
        return Mono.deferContextual(values -> {
            var preparation = ControlPreparation.find(values, context);
            if (preparation.isPresent()) {
                return Mono.just(grant(preparation.get(), context));
            }
            var snapshot = ReadOperationSnapshot.find(values, context);
            if (snapshot.isPresent()) {
                if (snapshot.get().configuration() == null)
                    return Mono.error(new IllegalStateException("Read operation did not request control configuration"));
                return Mono.just(grant(snapshot.get().configuration(), context));
            }
            Mono<Grant> result = Mono.fromCallable(() -> grant(context));
            return source == null ? result : result.subscribeOn(scheduler);
        });
    }

    /**
     * 本次操作已授权的配置快照；不再次查询来源。
     */
    public Grant grant(NewAiProperties configuration, ExecutionContext context) {
        var owner = ExecutionOwners.from(context);
        return configuration.grants().stream().filter(g -> g.enabled() && g.subjectId().equals(owner.subjectId())
                        && g.tenantId().equals(owner.tenantId()) && g.workspaceId().equals(owner.workspaceId())
                        && g.subjectName().equals(context.authorization().principal().subjectName())
                        && g.principalKind() == context.authorization().principal().kind()).findFirst()
                .orElseThrow(() -> {
                    log.warn("AI configuration grant unavailable, invocationId={}, traceId={}", context.executionId(), context.traceId());
                    return new AccessDeniedException("Configuration scope denied");
                });
    }
}
