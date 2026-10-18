package com.arte.ainew.spi.persistence;

import com.arte.ainew.pojo.execution.*;
import reactor.core.publisher.Mono;

import java.util.Objects;

/**
 * 从同一次 TOOL Attempt 已持久化的结果恢复终态；在同一事务验证过期租约、工作项领取、结果归属并推进 fencing。
 * 不创建 Attempt 或派发许可，不核对外部业务，也不把没有结果解释为未执行。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 20:47 ✾
 */
public interface ToolRecoveryStore {

    record CompleteStored(ExecutionCommands.Version invocation, OutboxMessage claim,
                          ExecutionPayload.Terminal terminal) {
        public CompleteStored {
            Objects.requireNonNull(invocation);
            Objects.requireNonNull(claim);
            Objects.requireNonNull(terminal);
            Objects.requireNonNull(terminal.result(), "Stored tool result is required");
        }
    }

    Mono<StoreOutcome<Invocation>> completeStoredToolResult(CompleteStored command);
}
