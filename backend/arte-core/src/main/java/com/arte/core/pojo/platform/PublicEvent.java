package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 同事务可靠事件。attributes 只放 epoch、变化类别、目标 ID 等白名单元数据，不放正文或密钥。
 *
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
