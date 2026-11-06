package com.arte.core.pojo.platform;

import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.utils.platform.PlatformChecks;

import java.util.Objects;

/**
 * 公开安全错误。客户端说明使用枚举描述；内部异常与凭据不得进入响应。
 *
 * @param code      对外公开的错误码
 * @param traceId   用于关联请求及服务端日志的跟踪 ID
 * @param retryable 当前错误是否可重试
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record CommandError(PlatformErrorCodeEnum code, String traceId, boolean retryable) {

    public CommandError {
        Objects.requireNonNull(code, "code");
        PlatformChecks.id(traceId, "traceId");
    }
}
