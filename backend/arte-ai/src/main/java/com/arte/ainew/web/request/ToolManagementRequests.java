package com.arte.ainew.web.request;

import com.arte.ainew.common.reference.DefinitionRef;
import com.arte.ainew.pojo.tool.ToolBindingSettings;
import com.arte.ainew.pojo.tool.ToolExecutionPolicy;
import com.arte.ainew.pojo.tool.ToolReleaseCommand;
import com.arte.core.pojo.platform.Scope;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import tools.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TOOL 管理白名单请求；Scope 仅是待验证选择，不接受 owner、actor、角色或执行 scopes。
 * 管理版本以十进制字符串传输，配置与示例使用标准 JSON，不使用内部多态快照格式。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/18 12:52 ✾
 */
public final class ToolManagementRequests {

    private ToolManagementRequests() {
    }

    /**
     * 不沿用全局忽略未知字段策略，白名单外字段明确拒绝。
     */
    public interface Strict {
        @JsonAnySetter
        default void unknown(String name, JsonNode value) {
            throw new IllegalArgumentException("Unknown tool request field");
        }
    }

    public record ToolScope(@NotBlank @Size(max = 256) String tenantId,
                            @NotBlank @Size(max = 256) String workspaceId) implements Strict {
        public Scope value() {
            return new Scope(tenantId, workspaceId);
        }
    }

    public record ListTools(@NotNull @Valid ToolScope scope, DefinitionRef provider, Boolean managed,
                            @Size(max = 132) String cursor, @Min(1) @Max(100) Integer size) implements Strict {
    }

    public record Page(@NotNull @Valid ToolScope scope, @Size(max = 132) String cursor,
                       @Min(1) @Max(100) Integer size) implements Strict {
    }

    public record FindTool(@NotNull @Valid ToolScope scope, @NotNull DefinitionRef tool,
                           Boolean managed) implements Strict {
    }

    public record FindBinding(@NotNull @Valid ToolScope scope,
                              @NotBlank @Size(max = 256) String bindingId) implements Strict {
    }

    public record RefreshLocalCatalog(@NotNull @Valid ToolScope scope,
                                      @NotBlank @Size(max = 1024) String reason) implements Strict {
    }

    public record FindRevision(@NotNull @Valid ToolScope scope, @NotNull DefinitionRef revision) implements Strict {
    }

    public record BindingHistory(@NotNull @Valid ToolScope scope, @NotBlank @Size(max = 256) String bindingId,
                                 @Size(max = 132) String cursor, @Min(1) @Max(100) Integer size) implements Strict {
    }

    public record ReleaseHistory(@NotNull @Valid ToolScope scope, @NotNull DefinitionRef tool,
                                 @Size(max = 132) String cursor, @Min(1) @Max(100) Integer size) implements Strict {
    }

    /**
     * timeoutMillis 是逻辑总预算；次数包含首次，退避为最低值，不代表重试授权。
     */
    public record Policy(@Min(1000) @Max(3600000) long timeoutMillis, @Min(1) @Max(10) int maxAttempts,
                         @Min(0) @Max(3600000) long retryBackoffMillis,
                         @Min(1) @Max(1000000) long maxInputBytes, @Min(1) @Max(33554432) long maxOutputBytes,
                         @NotNull ToolExecutionPolicy.ApprovalMode approvalMode) implements Strict {
    }

    public record Settings(@NotNull DefinitionRef tool, @NotNull ToolBindingSettings.VersionPolicy versionPolicy,
                           @NotBlank @Size(max = 200) String name, @NotNull Boolean enabled,
                           @NotNull @tools.jackson.databind.annotation.JsonDeserialize(using = com.arte.ainew.web.ToolJsonNodeDeserializer.class) JsonNode configuration,
                           @Size(max = 32) Map<String, DefinitionRef> secrets,
                           @Valid Policy policy,
                           @Size(max = 256) Set<@NotBlank @Size(max = 2048) String> networkOrigins) implements Strict {
    }

    public record SaveBinding(@NotNull @Valid ToolScope scope, @Size(max = 256) String bindingId,
                              @NotBlank @Pattern(regexp = "0|[1-9][0-9]{0,18}") String expectedVersion,
                              @NotBlank @Size(max = 1024) String reason,
                              @NotNull @Valid Settings settings) implements Strict {
    }

    public record SetBindingState(@NotNull @Valid ToolScope scope, @NotBlank @Size(max = 256) String bindingId,
                                  @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String expectedVersion,
                                  @NotBlank @Size(max = 1024) String reason,
                                  @NotNull Boolean enabled) implements Strict {
    }

    public record PreviewUpgrade(@NotNull @Valid ToolScope scope, @NotBlank @Size(max = 256) String bindingId,
                                 @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String expectedVersion,
                                 @NotNull @Valid Settings settings) implements Strict {
    }

    public record UpgradeBinding(@NotNull @Valid ToolScope scope, @NotBlank @Size(max = 256) String bindingId,
                                 @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String expectedVersion,
                                 @NotBlank @Size(max = 1024) String reason, @NotNull @Valid Settings settings,
                                 @NotBlank @Pattern(regexp = "[0-9a-f]{64}") String expectedPreviewDigest,
                                 Boolean acceptIncompatible) implements Strict {
    }

    public record Example(
            @NotNull @tools.jackson.databind.annotation.JsonDeserialize(using = com.arte.ainew.web.ToolJsonNodeDeserializer.class) JsonNode input,
            @NotNull @tools.jackson.databind.annotation.JsonDeserialize(using = com.arte.ainew.web.ToolJsonNodeDeserializer.class) JsonNode output) implements Strict {
    }

    public record Review(@NotEmpty @Size(max = 8) List<@NotNull @Valid Example> examples,
                         @NotBlank @Size(max = 2048) String riskEvidence, @Size(max = 2048) String idempotencyEvidence,
                         @NotNull ToolExecutionPolicy.ApprovalMode minimumApproval,
                         DefinitionRef compatibleFrom,
                         @Size(max = 2048) String compatibilityEvidence) implements Strict {
    }

    public record ValidateTool(@NotNull @Valid ToolScope scope, @NotNull DefinitionRef tool,
                               @NotNull @Valid Review review) implements Strict {
    }

    public record PublishTool(@NotNull @Valid ToolScope scope, @NotNull DefinitionRef tool,
                              @NotBlank @Pattern(regexp = "0|[1-9][0-9]{0,18}") String expectedVersion,
                              @NotBlank @Size(max = 1024) String reason,
                              @NotNull @Valid Review review) implements Strict {
    }

    /**
     * 仅 REENABLE／DEPRECATE／DISABLE；PUBLISH 使用独立且必须提供审核材料的入口。
     */
    public record SetToolState(@NotNull @Valid ToolScope scope, @NotNull DefinitionRef tool,
                               @NotNull ToolReleaseCommand.Action action,
                               @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String expectedVersion,
                               @NotBlank @Size(max = 1024) String reason,
                               java.time.Instant existingBindingsUntil) implements Strict {
    }
}
