package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 已有认证账户与稳定主体的不可变关联
 * <p>
 * 注销后保留 retiredAt，禁止重绑；当前主体状态仍由认证系统提供。
 * 登录名不入映射键；新注册账户即使同名，也必须使用新 externalUserId 和新 subjectId。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record PrincipalIdentity(String subjectId, String provider, String externalUserId,
                                Instant createdAt, Instant retiredAt, long version) {

    public PrincipalIdentity {
        PlatformChecks.id(subjectId, "subjectId");
        PlatformChecks.token(provider, "provider");
        PlatformChecks.id(externalUserId, "externalUserId");
        Objects.requireNonNull(createdAt, "createdAt");
        PlatformChecks.version(version, "version");
        PlatformChecks.require(retiredAt == null || !retiredAt.isBefore(createdAt), "retirement precedes creation");
    }
}
