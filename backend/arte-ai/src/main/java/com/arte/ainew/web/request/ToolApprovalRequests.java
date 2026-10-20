package com.arte.ainew.web.request;

import com.arte.ainew.pojo.tool.ToolApprovalCommand;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/**
 * 审批定位及决定白名单；不接收 owner、审批人、预算或执行权限。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 09:05 ✾
 */
public final class ToolApprovalRequests {

    private ToolApprovalRequests() {
    }

    public record Get(@NotNull @Valid ToolManagementRequests.ToolScope scope,
                      @Size(max = 256) String approvalId,
                      @Size(max = 256) String invocationId) implements ToolManagementRequests.Strict {
    }

    public record ListPending(@NotNull @Valid ToolManagementRequests.ToolScope scope,
                              @Pattern(regexp = "[0-9a-f]{64}") String cursor,
                              @Min(1) @Max(20) Integer limit) implements ToolManagementRequests.Strict {
    }

    public record Decide(@NotNull @Valid ToolManagementRequests.ToolScope scope,
                         @NotBlank @Size(max = 256) String approvalId,
                         @NotBlank @Pattern(regexp = "0|[1-9][0-9]{0,17}") String expectedVersion,
                         @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String subjectDigest,
                         @NotNull ToolApprovalCommand.Decision decision) implements ToolManagementRequests.Strict {
    }
}
