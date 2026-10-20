package com.arte.ainew.pojo.tool;

import com.arte.ainew.common.validation.ContractChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * Run 自身的耐久游标与步骤引用，不拼接子 Invocation 游标，不复制模型正文或工具参数。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 18:20 ✾
 */
public record ToolChatRunEvent(String runId, long sequence, ToolChatRun.State state, ToolChatRun.Checkpoint checkpoint,
                               Instant occurredAt) {

    public ToolChatRunEvent {
        ContractChecks.id(runId, "runId");
        ContractChecks.range(sequence, "Run event sequence", 1, Long.MAX_VALUE);
        Objects.requireNonNull(state);
        Objects.requireNonNull(checkpoint);
        Objects.requireNonNull(occurredAt);
    }

    public static ToolChatRunEvent of(ToolChatRun run) {
        return new ToolChatRunEvent(run.runId(), Math.incrementExact(run.version()), run.state(), run.checkpoint(), run.updatedAt());
    }
}
