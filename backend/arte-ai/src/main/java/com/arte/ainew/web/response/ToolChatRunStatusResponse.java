package com.arte.ainew.web.response;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.pojo.tool.ToolChatRun;

import java.time.Instant;

/**
 * 状态白名单投影；不返回 owner、输入快照、工具配置或预算账户。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 18:20 ✾
 */
public record ToolChatRunStatusResponse(String runId, ToolChatRun.State state, long version,
                                        ToolChatRun.Checkpoint checkpoint, int modelSteps, int toolSteps,
                                        boolean resultAvailable, boolean cancellationRequested,
                                        ExecutionError error, Instant acceptedAt, Instant updatedAt) {

    public static ToolChatRunStatusResponse from(ToolChatRun run) {
        return from(run, false);
    }

    public static ToolChatRunStatusResponse from(ToolChatRun run, boolean cancelling) {
        return new ToolChatRunStatusResponse(run.runId(), run.state(), run.version(), run.checkpoint(), run.modelSteps(), run.toolSteps(),
                run.result() != null, cancelling, run.error(), run.acceptedAt(), run.updatedAt());
    }
}
