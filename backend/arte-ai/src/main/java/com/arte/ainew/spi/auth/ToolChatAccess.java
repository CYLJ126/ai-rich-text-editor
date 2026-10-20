package com.arte.ainew.spi.auth;

import com.arte.ainew.common.execution.ExecutionContext;
import com.arte.ainew.pojo.tool.ToolChatRunSnapshot;
import reactor.core.publisher.Mono;

/**
 * Run 受理／推进与停止的宿主门控；复用当前身份、空间和工具资格，没有默认放行实现。
 * 停止使用本次控制期限，不要求原 Run 仍有新调用资格。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 22:00 ✾
 */
@FunctionalInterface
public interface ToolChatAccess {

    Mono<Permit> authorize(ExecutionContext current, boolean cancelling);

    interface Permit {
        /**
         * 在 Run 原写事务内重查当前资格；必须同时核验实际胜出的快照归属。
         */
        void requireInCurrentTransaction(ToolChatRunSnapshot snapshot);
    }
}
