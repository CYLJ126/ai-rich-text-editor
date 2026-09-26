package com.arte.ai.pojo.tool;

import com.arte.ai.common.enums.tool.ToolClusterEventTypeEnum;

import java.util.Objects;

/**
 * Redis 广播的工具目录变更事件，仅包含可跨节点传输的标识信息。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/9/26 ✾
 */
public record ToolClusterEvent(
        String eventId,
        String sourceInstanceId,
        ToolClusterEventTypeEnum eventType,
        String providerId,
        String namespace,
        String name,
        String version,
        long occurredAtEpochMilli
) {

    public ToolClusterEvent {
        eventId = requireText(eventId, "eventId");
        sourceInstanceId = requireText(sourceInstanceId, "sourceInstanceId");
        Objects.requireNonNull(eventType, "eventType");
        providerId = requireText(providerId, "providerId");
    }

    public ToolReference toolReference() {
        if (namespace == null || namespace.isBlank()
                || name == null || name.isBlank()
                || version == null || version.isBlank()) {
            return null;
        }
        return new ToolReference(namespace, name, version);
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
