package com.arte.core.pojo.access;

import com.arte.core.enums.access.AccessReasonEnum;
import com.arte.core.pojo.platform.AuthorizationVersions;
import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 单项授权结论；公开响应隐藏内部链路。拒绝可不返回版本，允许快照不是永久许可。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record AccessDecision(
        boolean allowed,
        AccessReasonEnum reason,
        AuthorizationVersions versions,
        Instant checkedAt) {
    public AccessDecision {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(checkedAt, "checkedAt");
        PlatformChecks.require(allowed == (reason == AccessReasonEnum.ALLOWED), "allow flag and reason mismatch");
        PlatformChecks.require(!allowed || versions != null, "allowed decision requires version basis");
    }
}
