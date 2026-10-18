package com.arte.ainew.spi.auth;

import com.arte.ainew.common.execution.ExecutionAuthorization;
import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.execution.Invocation;
import reactor.core.publisher.Mono;

/**
 * TOOL 历史读取与停止的当前宿主资格；独立于新调用资格，停用工具或关闭空间 AI 后仍可按当前权限收尾。
 * 无默认允许实现；不能凭原快照、调用 ID 或管理权限访问他人的执行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 20:47 ✾
 */
@FunctionalInterface
public interface ToolInvocationAccess {

    enum Operation {READ, CANCEL}

    Mono<Permit> authorize(Invocation invocation, ExecutionContext context, Operation operation);

    /**
     * 当前操作的权限上限；取消提交必须在执行存储的同一物理事务内再次验证并持有公共资格锁。
     */
    interface Permit {

        ExecutionAuthorization authorization();

        void requireInCurrentTransaction();
    }
}
