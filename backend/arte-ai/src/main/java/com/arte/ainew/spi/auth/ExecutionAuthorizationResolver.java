package com.arte.ainew.spi.auth;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * 可信身份与空间授权的组合端口。
 * 实现必须从已认证名称解析稳定主体 ID，验证 Tenant／Workspace 归属和请求的权限上限，
 * 拒绝停用、无权访问或无法确认的主体；返回可重检的授权引用。
 * 不允许仅复制请求体、Authentication 的角色集合或旧 ThreadLocal 来模拟资源授权。
 * 阻塞身份／授权存储应由实现使用专用有界执行资源隔离。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/4 20:50 ✾
 */
@FunctionalInterface
public interface ExecutionAuthorizationResolver {

    Mono<ExecutionAuthorization> resolve(String authenticatedName, String tenantId,
                                         String workspaceId, Set<String> requestedScopes);
}
