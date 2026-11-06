package com.arte.core.pojo.workspace;

import com.arte.core.enums.workspace.ClosureWorkStateEnum;
import com.arte.core.pojo.platform.Scope;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 记录已受理的空间关闭操作及其可持久化状态。资源是否已删除须由领域清理结果确认。
 *
 * @param id          关闭操作 ID
 * @param scope       目标空间范围，包含租户 ID 和空间 ID
 * @param requestedBy 提交关闭请求的主体 ID
 * @param reason      关闭原因
 * @param state       关闭操作当前状态
 * @param version     关闭操作记录版本
 * @param acceptedAt  关闭请求的受理时间
 * @param updatedAt   关闭操作记录的最近更新时间
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record ClosureOperation(
        String id,
        Scope scope,
        String requestedBy,
        String reason,
        ClosureWorkStateEnum state,
        long version,
        Instant acceptedAt,
        Instant updatedAt) {

    public ClosureOperation {
        PlatformChecks.id(id, "id");
        Objects.requireNonNull(scope, "scope");
        PlatformChecks.id(requestedBy, "requestedBy");
        reason = PlatformChecks.description(reason);
        Objects.requireNonNull(state, "state");
        PlatformChecks.version(version, "version");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        PlatformChecks.require(!updatedAt.isBefore(acceptedAt), "update precedes acceptance");
    }
}
