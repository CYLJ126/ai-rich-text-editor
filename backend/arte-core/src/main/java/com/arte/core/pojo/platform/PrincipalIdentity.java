package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

import java.time.Instant;
import java.util.Objects;

/**
 * 已有认证账户与稳定主体标识之间的不可变关联。
 * <p>
 * 退休时间保留并禁止重新绑定，当前账户状态仍以认证系统为准。
 * 登录名不作为映射键；新注册账户即使复用同一名称，也必须使用新的外部账户标识和主体标识。
 *
 * @param subjectId 稳定主体标识
 * @param provider 认证提供方标识
 * @param externalUserId 认证提供方分配的账户标识
 * @param createdAt 关联创建时间
 * @param retiredAt 退休时间；尚未退休时为空
 * @param version 用于乐观并发检查的非负版本号
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record PrincipalIdentity(String subjectId, String provider, String externalUserId,
                                Instant createdAt, Instant retiredAt, long version) {

    /**
     * 校验标识、版本号，以及创建时间和退休时间的先后关系。
     *
     * @throws IllegalArgumentException 标识、版本号或时间顺序无效时抛出
     * @throws NullPointerException     创建时间为空时抛出
     */
    public PrincipalIdentity {
        PlatformChecks.id(subjectId, "subjectId");
        PlatformChecks.token(provider, "provider");
        PlatformChecks.id(externalUserId, "externalUserId");
        Objects.requireNonNull(createdAt, "createdAt");
        PlatformChecks.version(version, "version");
        PlatformChecks.require(retiredAt == null || !retiredAt.isBefore(createdAt), "retirement precedes creation");
    }
}
