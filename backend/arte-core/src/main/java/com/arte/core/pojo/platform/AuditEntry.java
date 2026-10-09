package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 公共治理审计；实际 actor 与资源／执行归属分别记录。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record AuditEntry(
        String id,
        String actorSubjectId,
        CommandScope scope,
        String action,
        String operationId,
        String reason,
        Instant occurredAt) {
    public AuditEntry {
        PlatformChecks.id(id, "id");
        PlatformChecks.id(actorSubjectId, "actorSubjectId");
        Objects.requireNonNull(scope, "scope");
        PlatformChecks.token(action, "action");
        PlatformChecks.id(operationId, "operationId");
        reason = PlatformChecks.description(reason);
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
