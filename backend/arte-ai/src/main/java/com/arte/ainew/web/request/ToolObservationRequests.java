package com.arte.ainew.web.request;

import com.arte.ainew.pojo.execution.Invocation;
import com.arte.ainew.pojo.tool.ToolObservation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.Instant;

/**
 * 执行查询仅接收当前空间和筛选意图；未知字段明确拒绝，不接受主体或管理范围覆盖。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/22 14:40 ✾
 */
public final class ToolObservationRequests {

    private ToolObservationRequests() {
    }

    public record Query(@NotNull @Valid ToolManagementRequests.ToolScope scope, @NotNull Instant from,
                        @NotNull Instant until,
                        @Size(max = 256) String bindingId, @Size(max = 256) String toolId, Invocation.State state,
                        @Size(max = 256) String cursor,
                        @Min(1) @Max(20) Integer limit) implements ToolManagementRequests.Strict {
        public ToolObservation.Query value() {
            return new ToolObservation.Query(from, until, bindingId, toolId, state);
        }
    }
}
