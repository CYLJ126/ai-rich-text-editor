package com.arte.core.pojo.platform;

import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 公开安全错误。客户端说明使用枚举描述；内部异常与凭据不得进入响应。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record CommandError(
        PlatformErrorCodeEnum code,
        String traceId,
        boolean retryable) {
    public CommandError {
        Objects.requireNonNull(code, "code");
        PlatformChecks.id(traceId, "traceId");
    }
}
