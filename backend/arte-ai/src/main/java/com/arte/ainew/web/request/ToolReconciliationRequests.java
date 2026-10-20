package com.arte.ainew.web.request;

import com.arte.ainew.common.execution.ExecutionError;
import com.arte.ainew.pojo.tool.ToolOutcomeEvidence;
import com.arte.ainew.pojo.tool.ToolReconciliation;
import com.arte.ainew.serialization.CanonicalJson;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

/**
 * 人工证据严格白名单；主体由登录会话决定，版本使用十进制字符串。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/20 12:28 ✾
 */
public final class ToolReconciliationRequests {

    private ToolReconciliationRequests() {
    }

    public record Get(@NotNull @Valid ToolManagementRequests.ToolScope scope,
                      @NotBlank @Size(max = 256) String invocationId,
                      Boolean managed) implements ToolManagementRequests.Strict {
    }

    public record Page(@NotNull @Valid ToolManagementRequests.ToolScope scope,
                       @Pattern(regexp = "[0-9a-f]{64}") String cursor,
                       @Min(1) @Max(20) Integer limit) implements ToolManagementRequests.Strict {
    }

    public record Evidence(@NotNull ToolReconciliation.Source source, @NotNull ToolOutcomeEvidence.Decision decision,
                           @NotBlank @Size(max = 256) String reference,
                           @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String digest,
                           @NotNull ExecutionError.SideEffect sideEffect, @Size(max = 32768) String outputJson,
                           @NotBlank @Size(max = 1000) String verificationNote) implements ToolManagementRequests.Strict {
        public ToolReconciliation.Proposal value() {
            return new ToolReconciliation.Proposal(source, decision, reference, digest, sideEffect, CanonicalJson.structured(outputJson), verificationNote);
        }
    }

    public record Command(@NotNull @Valid ToolManagementRequests.ToolScope scope,
                          @NotBlank @Size(max = 256) String invocationId,
                          @NotBlank @Size(max = 256) String attemptId, @NotNull @Size(max = 256) String operationKey,
                          @NotBlank @Pattern(regexp = "0|[1-9][0-9]{0,18}") String expectedVersion,
                          @NotBlank @Pattern(regexp = "0|[1-9][0-9]{0,18}") String expectedInvocationVersion,
                          @NotBlank @Size(max = 1000) String reason, @NotBlank @Size(max = 1000) String nextStep,
                          @Valid Evidence evidence) implements ToolManagementRequests.Strict {
        public ToolReconciliation.Command value(ToolReconciliation.Action action) {
            return new ToolReconciliation.Command(invocationId, attemptId, operationKey, Long.parseLong(expectedVersion), Long.parseLong(expectedInvocationVersion), action, reason, nextStep, evidence == null ? null : evidence.value());
        }
    }
}
