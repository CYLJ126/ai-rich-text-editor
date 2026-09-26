package com.arte.ai.api.tool;

import com.arte.ai.common.enums.tool.ToolResultStatusEnum;
import com.arte.ai.pojo.tool.ToolArtifact;
import com.arte.ai.pojo.tool.ToolError;
import com.arte.ai.pojo.tool.ToolTaskHandle;
import com.arte.ai.pojo.tool.ToolUsage;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 工具执行统一结果。
 *
 * <p>使用 sealed hierarchy 表达成功、异步接受、暂停和失败等互斥形态，
 * 避免通过状态枚举配合大量可空字段。
 *
 * @param <O> 工具输出类型
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public sealed interface ToolResult<O extends ToolResponse> permits ToolResult.Succeeded,
        ToolResult.Accepted, ToolResult.Suspended, ToolResult.Unsuccessful {

    ToolResultStatusEnum status();

    Map<String, Object> metadata();

    record Succeeded<O extends ToolResponse>(
            O output,
            List<ToolContent> content,
            List<ToolArtifact> artifacts,
            ToolUsage usage,
            Map<String, Object> metadata
    ) implements ToolResult<O> {

        public Succeeded {
            if (output == null) {
                throw new IllegalArgumentException("output must not be null");
            }
            content = content == null ? List.of() : List.copyOf(content);
            artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
            metadata = immutable(metadata);
        }

        @Override
        public ToolResultStatusEnum status() {
            return ToolResultStatusEnum.SUCCEEDED;
        }
    }

    record Accepted<O extends ToolResponse>(
            ToolTaskHandle taskHandle,
            Map<String, Object> metadata
    ) implements ToolResult<O> {

        public Accepted {
            if (taskHandle == null) {
                throw new IllegalArgumentException("taskHandle must not be null");
            }
            metadata = immutable(metadata);
        }

        @Override
        public ToolResultStatusEnum status() {
            return ToolResultStatusEnum.ACCEPTED;
        }
    }

    record Suspended<O extends ToolResponse>(
            ToolResultStatusEnum status,
            String approvalRequestId,
            String resumeToken,
            Map<String, Object> metadata
    ) implements ToolResult<O> {

        public Suspended {
            if (status != ToolResultStatusEnum.REQUIRES_APPROVAL && status != ToolResultStatusEnum.PAUSED) {
                throw new IllegalArgumentException("suspended status must require approval or be paused");
            }
            if (resumeToken == null || resumeToken.isBlank()) {
                throw new IllegalArgumentException("resumeToken must not be blank");
            }
            if (status == ToolResultStatusEnum.REQUIRES_APPROVAL
                    && (approvalRequestId == null || approvalRequestId.isBlank())) {
                throw new IllegalArgumentException("approvalRequestId is required");
            }
            metadata = immutable(metadata);
        }
    }

    record Unsuccessful<O extends ToolResponse>(
            ToolResultStatusEnum status,
            ToolError error,
            ToolUsage usage,
            Map<String, Object> metadata
    ) implements ToolResult<O> {

        private static final Set<ToolResultStatusEnum> ALLOWED = Set.of(
                ToolResultStatusEnum.FAILED,
                ToolResultStatusEnum.DENIED,
                ToolResultStatusEnum.CANCELLED,
                ToolResultStatusEnum.TIMED_OUT
        );

        public Unsuccessful {
            if (!ALLOWED.contains(status)) {
                throw new IllegalArgumentException("invalid unsuccessful status: " + status);
            }
            if (error == null) {
                throw new IllegalArgumentException("error must not be null");
            }
            metadata = immutable(metadata);
        }
    }

    private static Map<String, Object> immutable(Map<String, Object> source) {
        return source == null ? Map.of() : Map.copyOf(source);
    }
}
