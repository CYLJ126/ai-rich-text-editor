package com.arte.ainew.spi.auth;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.execution.Invocation;
import org.springframework.security.access.AccessDeniedException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * TOOL 历史读取与停止的当前宿主资格；独立于新调用资格，停用工具或关闭空间 AI 后仍可按当前权限收尾。
 * 无默认允许实现；不能凭原快照、调用 ID 或管理权限访问他人的执行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 20:47 ✾
 */
@FunctionalInterface
public interface ToolInvocationAccess {

    /**
     * Run 控制沿用完整 owner 的当前资格；宿主须显式支持，不能以伪 Invocation 获取许可。
     */
    default Mono<Permit> authorizeOwner(com.arte.core.pojo.platform.ExecutionOwner owner,
                                        ExecutionContext context, Operation operation) {
        return Mono.error(new org.springframework.security.access.AccessDeniedException("Run control access unavailable"));
    }

    enum Operation {READ, CANCEL}

    Mono<Permit> authorize(Invocation invocation, ExecutionContext context, Operation operation);

    /**
     * 一次有界历史读取，最多 100 条；返回本次可读的调用许可，不跨批缓存。
     * 默认逐条授权以保留宿主的资源级规则；宿主只有在资格规则相同时才可合并公共事实读取。
     */
    default Mono<List<ReadPermit>> authorizeReadBatch(List<Invocation> invocations, ExecutionContext context) {
        if (invocations.size() > 100) return Mono.error(new IllegalArgumentException("Tool read batch exceeds limit"));
        return Flux.fromIterable(invocations).concatMap(invocation -> authorize(invocation, context, Operation.READ)
                        .map(permit -> new ReadPermit(invocation.request().context().executionId(), permit.authorization()))
                        .onErrorResume(AccessDeniedException.class, ignored -> Mono.empty()))
                .collectList();
    }

    /**
     * 只读许可没有事务写入能力；调用编号和授权范围仍由执行层复核。
     */
    record ReadPermit(String invocationId, ExecutionAuthorization authorization) {
    }

    /**
     * 当前操作的权限上限；取消提交必须在执行存储的同一物理事务内再次验证并持有公共资格锁。
     */
    interface Permit {

        ExecutionAuthorization authorization();

        void requireInCurrentTransaction();
    }
}
