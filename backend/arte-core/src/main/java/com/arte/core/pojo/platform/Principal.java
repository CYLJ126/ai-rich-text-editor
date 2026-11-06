package com.arte.core.pojo.platform;

import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 表示由可信身份提供者读取的稳定主体身份及当前状态。用户名不参与归属键。
 *
 * @param subjectId 主体的稳定身份标识
 * @param kind 主体类型
 * @param status 主体当前的账户状态
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record Principal(String subjectId, PrincipalKindEnum kind, PrincipalStatusEnum status) {

    public Principal {
        PlatformChecks.id(subjectId, "subjectId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
    }
}
