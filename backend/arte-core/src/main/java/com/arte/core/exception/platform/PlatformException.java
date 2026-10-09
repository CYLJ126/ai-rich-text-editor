package com.arte.core.exception.platform;

import com.arte.core.enums.platform.PlatformErrorCodeEnum;
import com.arte.core.utils.platform.PlatformChecks;
import lombok.Getter;

import java.util.Objects;

/**
 * 公共应用服务的类型化失败。HTTP 状态及 ResultCodeEnum 适配在 app 统一实现。
 */
@Getter
public final class PlatformException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final PlatformErrorCodeEnum code;
    private final String traceId;

    public PlatformException(PlatformErrorCodeEnum code, String traceId) {
        super(Objects.requireNonNull(code).getDescription());
        PlatformChecks.id(traceId, "traceId");
        this.code = code;
        this.traceId = traceId;
    }
}
