package com.arte.core.pojo.workspace;

import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.enums.workspace.CleanupAuthorizationStateEnum;
import com.arte.core.pojo.platform.Principal;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 内部验证快照；实际账本事务仍重检授权、工作项、有效期和数据关联。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record VerifiedCleanupAuthorization(
        CleanupAuthorization authorization,
        Principal actor,
        Instant checkedAt) {
    public VerifiedCleanupAuthorization {
        Objects.requireNonNull(authorization, "authorization");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(checkedAt, "checkedAt");
        PlatformChecks.require(actor.kind() == PrincipalKindEnum.SERVICE && actor.status() == PrincipalStatusEnum.ACTIVE
                && actor.subjectId().equals(authorization.actorSubjectId()), "cleanup requires active bound service actor");
        PlatformChecks.require(authorization.state() == CleanupAuthorizationStateEnum.ACTIVE && checkedAt.isBefore(authorization.expiresAt()), "cleanup authorization expired or revoked");
    }
}
