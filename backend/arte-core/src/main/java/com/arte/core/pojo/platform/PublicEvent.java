package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 承载与业务变更同事务写入发件箱的公共事件。元数据限于授权代次、变化类别和目标 ID 等白名单字段。
 *
 * @param id 事件 ID
 * @param scope 事件所属的命令范围
 * @param eventType 事件类型标识
 * @param schemaVersion 事件载荷的结构版本
 * @param version 事件对应的业务记录版本
 * @param occurredAt 事件发生时间
 * @param attributes 可公开的白名单事件元数据
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record PublicEvent(
        String id,
        CommandScope scope,
        String eventType,
        int schemaVersion,
        long version,
        Instant occurredAt,
        Map<String, String> attributes) {

    public PublicEvent {
        PlatformChecks.id(id, "id");
        Objects.requireNonNull(scope, "scope");
        PlatformChecks.token(eventType, "eventType");
        PlatformChecks.require(schemaVersion >= 1, "schemaVersion must be positive");
        PlatformChecks.version(version, "version");
        Objects.requireNonNull(occurredAt, "occurredAt");
        attributes = Map.copyOf(attributes);
    }
}
