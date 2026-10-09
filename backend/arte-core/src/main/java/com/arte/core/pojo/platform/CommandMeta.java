package com.arte.core.pojo.platform;

import com.arte.core.utils.platform.PlatformChecks;

/**
 * 所有写命令必填。主体、命令类型及操作范围由应用服务生成，不接受客户端授权声明。
 *
 * @author CYLJ126 ≧◔◡◔≦
 * @since 2026/10/09 14:25 ✾
 */
public record CommandMeta(String operationKey, String traceId) {

    public CommandMeta {
        PlatformChecks.id(operationKey, "operationKey");
        PlatformChecks.id(traceId, "traceId");
    }
}
