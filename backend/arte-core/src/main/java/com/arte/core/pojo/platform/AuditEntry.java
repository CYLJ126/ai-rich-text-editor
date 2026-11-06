package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 公共治理审计；实际 actor 与资源／执行归属分别记录。
 *
 * @param id             审计条目 ID
 * @param actorSubjectId 实际执行操作的主体 ID
 * @param scope          被审计操作的范围
 * @param action         被审计的动作标识
 * @param operationId    关联的操作 ID
 * @param reason         审计记录的操作原因
 * @param occurredAt     操作发生时间
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
