package com.arte.core.pojo.platform;

import com.arte.core.enums.platform.PrincipalKindEnum;
import com.arte.core.enums.platform.PrincipalStatusEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 稳定主体。当前状态由可信身份提供者读取，用户名不参与归属键。
 *
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
