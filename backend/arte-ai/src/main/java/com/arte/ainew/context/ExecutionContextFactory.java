package com.arte.ainew.context;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.spi.auth.ExecutionAuthorizationResolver;
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
        return authorize(authenticatedName, request.tenantId(), request.workspaceId(), request.scopes(), deadline)
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
