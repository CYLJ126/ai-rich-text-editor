package com.arte.ainew.context;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
import com.arte.core.diagnostics.RequestObservation;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolder;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeoutException;

/**
 * 认证入口的上下文工厂
 * <p>
 * 显式注入权威授权解析器，不自动注册或修改旧认证链路。
 * <p>
 * <ol>
 *     <li>MVC 身份在方法调用时捕获；</li>
 *     <li>WebFlux 身份在订阅时从响应式 SecurityContext 读取；</li>
 *     <li>不保存 Authentication 或在线用户对象；</li>
 *     <li>无身份、空解析结果或范围不一致均拒绝。</li>
 * * </ol>
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 20:50 ✾
 */
public final class ExecutionContextFactory {

    private final ExecutionAuthorizationResolver authorizationResolver;
    private final Clock clock;

    public ExecutionContextFactory(ExecutionAuthorizationResolver authorizationResolver, Clock clock) {
        this.authorizationResolver = Objects.requireNonNull(authorizationResolver, "authorizationResolver");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * 必须在 MVC 请求线程中调用，不能将 SecurityContextHolder 读取延迟到异步线程。
     */
    public Mono<ExecutionContext> createCurrent(ExecutionContextRequest request) {
        return create(SecurityContextHolder.getContext().getAuthentication(), request);
    }

    /**
     * 捕获已认证的 MVC 身份，并在每次订阅时为操作准备一次当前资格和配置。
     * <p>
     * 必须在 MVC 请求线程调用。延迟执行的操作仅保留捕获的名称，不持有可变认证对象。
     * 支持准备能力的解析器一次提供当前资格和配置；其他解析器使用普通上下文创建流程。
     * 每次订阅均取得新的准备结果，请求超时覆盖整个操作。
     *
     * @param request 请求的租户、工作空间、权限上限、超时和执行引用
     * @param work    使用已验证执行上下文执行的操作
     * @param <T>     操作结果类型
     * @return 操作结果，或认证、授权、准备、超时错误
     */
    public <T> Mono<T> prepareCurrent(ExecutionContextRequest request,
                                      java.util.function.Function<ExecutionContext, Mono<T>> work) {
        Authentication captured = SecurityContextHolder.getContext().getAuthentication();
        if (!(authorizationResolver instanceof com.arte.ainew.spi.auth.ExecutionPreparationAuthorizationResolver preparations)
                || captured == null || !captured.isAuthenticated() || captured instanceof AnonymousAuthenticationToken)
            return create(captured, request).flatMap(work).timeout(request.timeout());
        String name = captured.getName();
        if (name == null || name.isBlank())
            return Mono.error(new AuthenticationCredentialsNotFoundException("Authenticated identity is required"));
        return Mono.defer(() -> {
            var prepared = new java.util.concurrent.atomic.AtomicReference<com.arte.ainew.spi.auth.ExecutionPreparationAuthorizationResolver.Preparation>();
            var scoped = new ExecutionContextFactory((authenticatedName, tenant, workspace, scopes) ->
                    preparations.prepare(authenticatedName, tenant, workspace, scopes)
                            .doOnNext(prepared::set)
                            .map(com.arte.ainew.spi.auth.ExecutionPreparationAuthorizationResolver.Preparation::authorization), clock);
            return scoped.create(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(name, null, java.util.List.of()), request)
                    .flatMap(context -> ControlPreparation.use(context,
                            new com.arte.ainew.spi.auth.AiControlConfigurationSource.PreparedConfiguration(
                                    prepared.get().configuration(), prepared.get().catalogRevision()), work));
        }).timeout(request.timeout());
    }

    /**
     * MVC 身份仍在调用时捕获；快照按订阅创建，数据库读取完成后才进入本次只读操作。
     */
    public <T> Mono<T> readCurrent(ExecutionContextRequest request, boolean includeConfiguration,
                                   java.util.function.Function<ExecutionContext, Mono<T>> work) {
        Authentication captured = SecurityContextHolder.getContext().getAuthentication();
        if (!(authorizationResolver instanceof com.arte.ainew.spi.auth.ExecutionReadAuthorizationResolver reads))
            return create(captured, request).flatMap(work).timeout(request.timeout());
        if (captured == null || !captured.isAuthenticated() || captured instanceof AnonymousAuthenticationToken)
            return create(captured, request).flatMap(work);
        // 与 create 一样，只复制请求线程验证后的名称，订阅闭包不持有在线用户或 Authentication。
        String name = captured.getName();
        if (name == null || name.isBlank())
            return Mono.error(new AuthenticationCredentialsNotFoundException("Authenticated identity is required"));
        return Mono.defer(() -> {
            var snapshot = new java.util.concurrent.atomic.AtomicReference<com.arte.ainew.spi.auth.ExecutionReadAuthorizationResolver.Snapshot>();
            var scoped = new ExecutionContextFactory((authenticatedName, tenant, workspace, scopes) ->
                    reads.readSnapshot(authenticatedName, tenant, workspace, scopes, includeConfiguration)
                            .doOnNext(snapshot::set).map(com.arte.ainew.spi.auth.ExecutionReadAuthorizationResolver.Snapshot::authorization), clock);
            return scoped.create(org.springframework.security.authentication.UsernamePasswordAuthenticationToken.authenticated(name, null, java.util.List.of()), request)
                    .flatMap(context -> ReadOperationSnapshot.use(context, snapshot.get(), work));
        }).timeout(request.timeout());
    }

    public Mono<ExecutionContext> createReactive(ExecutionContextRequest request) {
        return ReactiveSecurityContextHolder.getContext()
                .switchIfEmpty(Mono.error(new AuthenticationCredentialsNotFoundException("Authenticated identity is required")))
                .flatMap(security -> create(security.getAuthentication(), request));
    }

    /**
     * 根据已认证身份、执行上下文请求参数创建执行上下文
     * <p>
     * 验证已认证身份，生成执行 ID 和跟踪 ID，解析授权引用。
     *
     * @param authentication 已认证身份
     * @param request        执行上下文请求参数
     * @return 执行上下文 Mono 流
     */
    public Mono<ExecutionContext> create(Authentication authentication, ExecutionContextRequest request) {
        Objects.requireNonNull(request, "request");
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken) {
            return Mono.error(new AuthenticationCredentialsNotFoundException("Authenticated identity is required"));
        }
        // 只捕获验证后的名称，后续不读取可变 Authentication 或 ThreadLocal。
        String authenticatedName = authentication.getName();
        if (authenticatedName == null || authenticatedName.isBlank()) {
            return Mono.error(new AuthenticationCredentialsNotFoundException("Authenticated identity is required"));
        }
        String executionId = UUID.randomUUID().toString();
        String traceId = request.traceId() == null ? UUID.randomUUID().toString().replace("-", "") : request.traceId();
        var deadline = clock.instant().plus(request.timeout());
        // 先解析并验证授权引用，再创建执行上下文
        return RequestObservation.initialAuthorization(authorize(authenticatedName, request.tenantId(), request.workspaceId(), request.scopes(), deadline))
                .map(authorization -> new ExecutionContext(executionId, traceId, authorization, deadline, null,
                        request.budgetRef(), request.releaseRef(), request.idempotencyKey()));
    }

    /**
     * 从可信执行记录重建上下文，保留身份、关联与总期限，并更新授权引用。
     * <p>
     * 调用方必须先验证 Worker 服务身份、任务领取归属及记录完整性；此方法不能暴露为
     * 接收客户端 ExecutionContext 的接口，也不读取或恢复旧进程的取消对象。
     * 持久化取消状态由执行控制层核对，重建上下文本身不授权重新发送外部副作用。
     */
    public Mono<ExecutionContext> restore(ExecutionContext persisted) {
        Objects.requireNonNull(persisted, "persisted");
        var original = persisted.authorization();
        return authorize(original.principal().subjectName(), original.tenantId(), original.workspaceId(),
                original.scopes(), persisted.deadline())
                .map(authorization -> {
                    if (!original.principal().equals(authorization.principal())) {
                        throw new AccessDeniedException("Persisted execution identity has changed");
                    }
                    return new ExecutionContext(persisted.executionId(), persisted.traceId(), authorization,
                            persisted.deadline(), persisted.parentExecutionId(), persisted.budgetRef(),
                            persisted.releaseRef(), persisted.idempotencyKey());
                });
    }

    /**
     * 根据请求参数解析授权引用
     * <p>
     * 解析授权引用，并验证授权引用是否匹配请求参数，验证授权引用是否在有效期内。
     *
     * @param authenticatedName 已认证名称（从 Spring Security 获取来的）
     * @param tenantId          Tenant ID
     * @param workspaceId       Workspace ID
     * @param scopes            请求的权限上限
     * @param deadline          执行截止时间
     * @return 授权引用 Mono 流
     */
    private Mono<ExecutionAuthorization> authorize(String authenticatedName, String tenantId,
                                                   String workspaceId, Set<String> scopes, Instant deadline) {
        return Mono.defer(() -> {
                    Duration remaining = Duration.between(clock.instant(), deadline);
                    if (remaining.isNegative() || remaining.isZero()) {
                        return Mono.error(new TimeoutException("Execution deadline exceeded during authorization"));
                    }
                    // 根据请求参数解析授权引用，得到 ExecutionAuthorization 对象
                    return authorizationResolver.resolve(authenticatedName, tenantId, workspaceId, scopes).timeout(remaining);
                })
                .switchIfEmpty(Mono.error(new AccessDeniedException("Execution authorization was not resolved")))
                .map(authorization -> {
                    // 验证授权引用是否匹配请求参数
                    if (!authenticatedName.equals(authorization.principal().subjectName())
                            || !tenantId.equals(authorization.tenantId())
                            || !workspaceId.equals(authorization.workspaceId())
                            || !scopes.containsAll(authorization.scopes())) {
                        throw new AccessDeniedException("Resolved authorization does not match requested scope");
                    }
                    // 验证授权引用是否在有效期内
                    if (!clock.instant().isBefore(deadline)) {
                        throw reactor.core.Exceptions.propagate(
                                new TimeoutException("Execution deadline exceeded during authorization"));
                    }
                    return authorization;
                });
    }
}
