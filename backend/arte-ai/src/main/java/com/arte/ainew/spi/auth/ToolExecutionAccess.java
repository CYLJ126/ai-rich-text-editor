package com.arte.ainew.spi.auth;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.pojo.tool.LocalToolCall;
import reactor.core.publisher.Mono;

/**
 * 工具实际执行前的宿主授权端口，不提供默认允许实现。
 * 组合模块须复用当前身份／空间／AI 资格和工具调用权限，并按固定参数检查业务资源及外发资格；不能只复制旧 scopes。
 * 领域适配在实际 Query／Command 边界仍须执行所属领域的权限与版本检查，预检不冻结授权。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 15:21 ✾
 */
@FunctionalInterface
public interface ToolExecutionAccess {

    /**
     * 每次调用重新授权，返回同一主体／租户／空间内的权限子集；空结果视为拒绝。
     * 实现自行隔离阻塞授权读取，不在 AI 网关另建身份或 RBAC 权威。
     */
    Mono<ExecutionAuthorization> authorize(LocalToolCall call);

    /**
     * 远端调用须由宿主明确授权外发；旧本地授权实现默认拒绝，不能自动扩大权限。
     */
    default Mono<ExecutionAuthorization> authorizeRemote(com.arte.ainew.pojo.tool.RemoteToolCall call) {
        return Mono.error(new org.springframework.security.access.AccessDeniedException("Remote tool access denied"));
    }
}
