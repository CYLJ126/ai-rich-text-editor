package com.arte.base.model.artifact;

import com.arte.base.model.identity.ExecutionScope;
import com.arte.base.validation.ContractChecks;

import java.time.Instant;

/**
 * 产物元数据快照；文件字节由 ArtifactStore 管理。
 *
 * 独立私有产物归属绑定完整 scope；revision 支持生命周期条件更新。
 */
public record Artifact(
        ArtifactRef ref,
        ExecutionScope scope,
        ArtifactStatus status,
        long revision,
        Instant createdAt,
        Instant expiresAt
) {
    public Artifact {
        ref = ContractChecks.required(ref, "ref");
        scope = ContractChecks.required(scope, "scope");
        status = ContractChecks.required(status, "status");
        createdAt = ContractChecks.required(createdAt, "createdAt");
        if (revision <= 0) throw new IllegalArgumentException("revision must be positive");
        if (expiresAt != null && !expiresAt.isAfter(createdAt))
            throw new IllegalArgumentException("expiresAt must follow createdAt");
        if (status == ArtifactStatus.REFERENCED && expiresAt != null)
            throw new IllegalArgumentException("referenced artifact cannot retain temporary expiry");
    }

    public boolean isReadableAt(Instant now) {
        ContractChecks.required(now, "now");
        return (status == ArtifactStatus.AVAILABLE || status == ArtifactStatus.REFERENCED)
                && (expiresAt == null || now.isBefore(expiresAt));
    }
}
