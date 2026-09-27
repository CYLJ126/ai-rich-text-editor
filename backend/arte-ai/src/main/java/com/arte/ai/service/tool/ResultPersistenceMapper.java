package com.arte.ai.service.tool;

import com.arte.ai.api.tool.ToolContent;
import com.arte.ai.api.tool.ToolResponse;
import com.arte.ai.api.tool.ToolResult;
import com.arte.ai.common.enums.tool.CategoryEnum;
import com.arte.ai.common.enums.tool.ToolResultStatusEnum;
import com.arte.ai.pojo.tool.DynamicToolResponse;
import com.arte.ai.pojo.tool.ToolError;
import com.arte.ai.pojo.tool.po.ToolCallResultPo;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * 工具统一结果与持久化快照的转换器。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/27 ✾
 */
final class ResultPersistenceMapper {

    private ResultPersistenceMapper() {
    }

    static ToolCallResultPo toPo(ToolResult<? extends ToolResponse> result, String callId,
                                 String taskId, ObjectMapper objectMapper) {
        ToolCallResultPo po = new ToolCallResultPo()
                .setResultId(UUID.randomUUID().toString()).setCallId(callId).setTaskId(taskId)
                .setStatus(result.status()).setMetadata(result.metadata()).setCompletedAt(LocalDateTime.now());
        if (result instanceof ToolResult.Succeeded<?> succeeded) {
            po.setOutput(asMap(succeeded.output(), objectMapper));
            po.setContent(asMapList(succeeded.content(), objectMapper));
            po.setArtifacts(asMapList(succeeded.artifacts(), objectMapper));
            po.setUsageInfo(asMap(succeeded.usage(), objectMapper));
        } else if (result instanceof ToolResult.Unsuccessful<?> unsuccessful) {
            po.setErrorInfo(asMap(unsuccessful.error(), objectMapper));
            po.setUsageInfo(asMap(unsuccessful.usage(), objectMapper));
        } else if (result instanceof ToolResult.Accepted<?> accepted) {
            po.setOutput(Map.of("taskId", accepted.taskHandle().taskId()));
        } else if (result instanceof ToolResult.Suspended<?> suspended) {
            po.setOutput(Map.of("approvalRequestId", nullToEmpty(suspended.approvalRequestId())));
        }
        return po;
    }

    static ToolResult<? extends ToolResponse> fromPo(ToolCallResultPo po, ObjectMapper objectMapper) {
        if (po.getStatus() == ToolResultStatusEnum.SUCCEEDED) {
            Map<String, Object> output = po.getOutput() == null ? Map.of() : po.getOutput();
            DynamicToolResponse response = new DynamicToolResponse(output.getOrDefault("value", output),
                    output.get("rawContent") == null ? null : String.valueOf(output.get("rawContent")));
            return new ToolResult.Succeeded<>(response, content(po.getContent()), List.of(), null,
                    safeMap(po.getMetadata()));
        }
        Map<String, Object> error = safeMap(po.getErrorInfo());
        CategoryEnum category = parseCategory(error.get("category"));
        ToolError toolError = new ToolError(String.valueOf(error.getOrDefault("code", "TOOL_FAILED")),
                category, String.valueOf(error.getOrDefault("message", "tool execution failed")),
                Boolean.TRUE.equals(error.get("retryable")), safeNestedMap(error.get("details")));
        ToolResultStatusEnum status = switch (po.getStatus()) {
            case DENIED, CANCELLED, TIMED_OUT, FAILED -> po.getStatus();
            default -> ToolResultStatusEnum.FAILED;
        };
        return new ToolResult.Unsuccessful<>(status, toolError, null, safeMap(po.getMetadata()));
    }

    private static List<ToolContent> content(List<Map<String, Object>> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().map(value -> (ToolContent) new ToolContent.Structured(value, Map.of())).toList();
    }

    private static CategoryEnum parseCategory(Object value) {
        try {
            return CategoryEnum.valueOf(String.valueOf(value).toUpperCase(Locale.ROOT));
        } catch (Exception ignored) {
            return CategoryEnum.INTERNAL;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value, ObjectMapper objectMapper) {
        if (value == null) {
            return null;
        }
        Object converted = objectMapper.convertValue(value, Object.class);
        return converted instanceof Map<?, ?> map
                ? Map.copyOf((Map<String, Object>) map) : Map.of("value", converted);
    }

    private static List<Map<String, Object>> asMapList(List<?> values, ObjectMapper objectMapper) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(value -> asMap(value, objectMapper)).toList();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> safeNestedMap(Object value) {
        return value instanceof Map<?, ?> map ? Map.copyOf((Map<String, Object>) map) : Map.of();
    }

    private static Map<String, Object> safeMap(Map<String, Object> value) {
        return value == null ? Map.of() : value;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
