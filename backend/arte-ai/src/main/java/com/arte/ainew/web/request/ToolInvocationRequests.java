package com.arte.ainew.web.request;

import com.arte.ainew.pojo.tool.ToolAdmissionInput;
import com.arte.ainew.web.ToolManagementValues;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;

import java.time.Duration;

/**
 * 独立 TOOL 调用白名单；仅提交意图，不接受身份、执行器、预算或权限快照。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/19 09:47 ✾
 */
public final class ToolInvocationRequests {
    private ToolInvocationRequests() {
    }

    public record Input(@NotBlank @Size(max = 256) String bindingId,
                        @NotNull @tools.jackson.databind.annotation.JsonDeserialize(using = com.arte.ainew.web.ToolJsonNodeDeserializer.class) JsonNode arguments,
                        @Min(1) @Max(3600) Integer timeoutSeconds,
                        @Min(1) @Max(10) Integer maxAttempts,
                        @Min(1) @Max(33554432) Long maxOutputBytes) implements ToolManagementRequests.Strict {
        public ToolAdmissionInput value() {
            return new ToolAdmissionInput(bindingId, ToolManagementValues.object(arguments, false),
                    timeoutSeconds == null ? null : Duration.ofSeconds(timeoutSeconds), maxAttempts, maxOutputBytes);
        }
    }

    public record Preview(@NotNull @Valid ToolManagementRequests.ToolScope scope,
                          @NotNull @Valid Input input) implements ToolManagementRequests.Strict {
    }

    public record Submit(@NotNull @Valid ToolManagementRequests.ToolScope scope,
                         @NotNull @Valid Input input,
                         @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedPlanDigest) implements ToolManagementRequests.Strict {
    }
}
